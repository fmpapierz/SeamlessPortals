# S12-B — the CLIENT mixins (second half of U10) + the HARD CLOSURE GATE

**Stage S12-B of the entity-portal migration — the LAST stage before S13 SCC closure / first light.**
S12-B ports the CLIENT half of IP's client-mixin set (`api-map/mixin-client.md`, both the
multiworld/sync/sound/interaction/collision half and the render/particle half — 62 inventory mixins),
then runs the **last pre-closure HARD GATE**: the probe (`:common:compileJava -Pip_scc_closed=true`) may
reference ONLY the S13 closure set. All ports land under wholesale-held `qouteall/imm_ptl/core/mixin/client/**`
(+ the plan-justified `compat/IPModInfoChecking`), **held-UNREGISTERED** (no `mixins.json` edit —
registration is S13). ZERO IP-logic deviation; every GONE/CHANGED 26.2 API translated per the api-maps;
every `@IPVanillaCopy` body re-derived line-by-line from 26.2; every synthetic-lambda anchor re-derived
from the COMPILED 26.2 jar.

This note consolidates the three working fragments (`S12B-multiworld.md` = Slice A, `S12B-render.md` =
Slice B, `S12B-behavior.md` = Slice C) with the evidence. The companion note **`S12B-gate-closure.md`**
holds the granular record of the three category-(c) translation-slip fixes and the ratified
`ShaderCodeTransformation` gate-set amendment; §7–§8 here summarize its verdict and cross-reference it.

**Citation conventions:** `IP:` = 1.21.3 (`ImmersivePortalsMod/.../qouteall`); `26.2:` = `mc262-ref`
(Mojang mappings); `MOD:` = live `com.warwa.seamlessportals`; `MOD-qouteall:` = held ports.

---

## 1. Per-mixin ledger — every `mixin-client.md` row dispositioned (both halves)

**Disposition legend.** `LANDED` = held-UNREGISTERED file compiles clean this stage. `LANDED-INERT` =
empty/no-op body (target-solved-by-vanilla or cosmetic). `DEFERRED` = 26.2 anchor worked out + pinned,
file held inert, wired at S13 (dependency not yet in the closure set, or extract/render-split /
FrontClipping design pending). `DROPPED` = TARGET-GONE with no held file (a `@Mixin` on the gone target
would fail to compile). `RETIRED` = removed by decision (superseded / solved-by-vanilla). `PRIOR-STAGE` =
already landed at an earlier stage; row shown for completeness.

api-map header verdict counts: **PORTS-CLEAN 22 · NEEDS-RETARGET 26 · TARGET-GONE 14** (all 62 accounted).

### 1.1 top-level `mixin/client/` (api-map §1)

| IP mixin | api-map verdict | S12-B disposition | Key 26.2 retarget / note |
|---|---|---|---|
| `MixinAbstractClientPlayer` | NEEDS-RETARGET | **LANDED** | `clientLevel` field GONE; class made `abstract`, `@Shadow`s inherited `Entity.setLevel(Level)` (`26.2:Entity.java:3962`); `ip_setClientLevel` calls it. WATCH (S13): confirm Mixin's shadow-method hierarchy resolution of the inherited `setLevel` when the client set registers |
| `MixinClientConnection` | PORTS-CLEAN | **LANDED** | empty placeholder, verbatim (`Connection`) |
| `MixinClientLevel` | NEEDS-RETARGET | **PRIOR-STAGE (S10-C)** | ctor swap + final→`@Mutable`; `mapData` now `Map<MapId,…>` (drives §8.1 fix) |
| `MixinDebugScreenOverlay` | TARGET-GONE | **DEFERRED** | `getSystemInformation()` GONE; F3 = private `DebugScreenEntries` registry + profile-enable = a design task. Landed inert (cosmetic F3, non-load-bearing) |
| `MixinGlDebug` | NEEDS-RETARGET | **LANDED** | package → `com.mojang.blaze3d.opengl.GlDebug.printDebugLog(IIIIIJJ)` (private instance); handler drops `static`. GL-backend-only |
| `MixinLivingEntity_C` | NEEDS-RETARGET | **LANDED** | `lerpTo`/`lerpX/Y/Z` GONE → `InterpolationHandler.interpolateTo(Vec3,F,F)` @RETURN. **PHASE tick/network.** Behavior note: fires for all interpolating entities now, guarded `ip_getCollidingPortal()!=null` (benign). WATCH (S13): live-verify the broadening at first entity-through-portal |
| `MixinMinecraft` | NEEDS-RETARGET | **LANDED** | see §1.9 |
| `MixinGui` *(NEW, re-home of MixinMinecraft handler ⑦)* | — | **DEFERRED** | `addInitialScreens` moved `Minecraft`→`Gui` (`26.2:Gui.java:381`); 26.2 anchor fully worked out in-file, but `IPortalInitialScreen` (`miscellaneous` GUI) NOT in the closure set → referencing it would break the gate. Landed inert; re-enable when the `miscellaneous` GUI cluster lands (non-load-bearing splash) |

### 1.2 `accessor/` (api-map §2)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `CoreShadersAccessor` | TARGET-GONE | **RETIRED** (S12-A decision) | `CoreShaders.register` G9-GONE; custom shaders = mod `RenderPipelines`. Not landed |
| `IEClientLevelData` | PORTS-CLEAN | **LANDED** | verbatim (`ClientLevel.ClientLevelData.isFlat`) |
| `IEClientLevel_Accessor` | PORTS-CLEAN | **LANDED** | map key retype `String`→`MapId` (matches landed `MixinClientLevel`) — CORRECT; it exposed the S10 `ClientWorldLoader` slip closed in §8.1 |

### 1.3 `collisions/` + `debug/` (api-map §3)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `MixinLocalPlayer` | PORTS-CLEAN | **LANDED** | verbatim (`LocalPlayer.suffocatesAt(BlockPos)` HEAD, private OK) |
| `MixinClientPacketListener_Debug` | PORTS-CLEAN | **LANDED** | empty, verbatim |

### 1.4 `interaction/` (api-map §4 — all reference `block_manipulation` = S13 closure forward-ref, ALLOWED)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `MixinItem_Interaction_Client` | PORTS-CLEAN | **LANDED** | empty, verbatim |
| `MixinGameRenderer_B` | NEEDS-RETARGET | **LANDED** | `@Mixin` target GameRenderer→`Minecraft` (`pick(F)` moved, `26.2:Minecraft.java:2934`); file kept at IP path |
| `MixinMinecraft_B` | NEEDS-RETARGET | **LANDED** | pick invoke renamed `pickBlock`→`pickBlockOrEntity()` (`:2353`). S13: `startUseItem()` has TWO invoke sites (`:1944,:1959`), ordinal-less `@WrapOperation` wraps both (faithful). Forward-refs `BlockManipulationClient` (S13) |
| `MixinMultiPlayerGameMode` | NEEDS-RETARGET | **LANDED** | see §1.10. ⚠L lambda-name re-derivation at S13; `BlockManipulationServer` = S13 |
| `IEUseOnContext` *(NEW support)* | — | **LANDED** | protected 5-arg `UseOnContext.<init>` ctor `@Invoker` (no AW/AT) → lets `MixinMultiPlayerGameMode` build against the switched `mc.level` |

### 1.5 `multiworld_awareness/` (api-map §5)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `MixinBiomeAmbientSoundPlayer` | TARGET-GONE | **LANDED-INERT** | `biomeManager` GONE; vanilla `tick()` reads ambience live from `player.level()` each tick (`:45-49`) → IP's re-point is solved by vanilla. Empty inert body; verify once in-game after dim switch |
| `MixinFogRenderer` | TARGET-GONE | **LANDED (R9 re-expressed)** | SIX fog statics GONE; static-init installs the (now no-op) `FogRendererContext` hooks + `FogRendererContext.init()` (lifecycle contract preserved). **PHASE:** installer at class-load; the fog color-probe it arms (`getFogColorOf`) is inside-render/dest-pass (CUTOVER_SPEC §3.3) |

### 1.6 `particle/` (api-map §6)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `IEParticle` | PORTS-CLEAN | **LANDED** (Slice B) | `Particle.level` accessor unchanged (now `final`) |
| `MixinParticleEngine` | NEEDS-RETARGET | **LANDED** (Slice C authored, Slice B kept) | far-portal render-skip re-sites `render`→`extract(ParticlesRenderState,Frustum,Camera,F)` HEAD-cancel. **PHASE inside-render/dest-pass extract** (reads `PortalRendering.isRendering()`; main-frame extract has it false — CUTOVER_SPEC §4.2). `ip_setWorld` duck ported. **DEFERRED (targets GONE):** per-particle render filter (`Particle.render` gone) + wrong-dim tick-skip (`tickParticle`→`ParticleGroup.tickParticles`) → S13 |

### 1.7 `render/` top-level (api-map §7)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `IERenderSystem` | PORTS-CLEAN | **PRIOR-STAGE (S11/S12-A)** | `modelViewStack` accessor |
| `IESectionRenderDispatcher` | PORTS-CLEAN | **PRIOR-STAGE (S12-A)** | `fixedBuffers` accessor |
| `MixinBlockEntityRenderDispatcher` | PORTS-CLEAN | **LANDED** | empty body |
| `MixinCamera` | NEEDS-RETARGET | **LANDED** | R13k — see §3 |
| `MixinEntityRenderDispatcher` | PORTS-CLEAN | **PRIOR-STAGE (S12-A)** | `shouldRender` HEAD, now extract-time gate |
| `MixinFrustum_FixDeadLoop` | NEEDS-RETARGET | **LANDED (@Overwrite re-copy)** | body re-derived from `26.2:Frustum.java:46-71` + isometric early-out + 10-iter cap |
| `MixinGameRenderer` | NEEDS-RETARGET | **LANDED (RECONCILED)** | A2 bob + IEGameRenderer ducks + R13k ⑪ — see §3/§4 |
| `MixinGlStateManager` | NEEDS-RETARGET | **LANDED** | package → `com.mojang.blaze3d.opengl` (GL-backend-only); `_enableCull` cancel funnel |
| `MixinLevelRenderer` | NEEDS-RETARGET | **PRIOR-STAGE (S12-A, partial)** | R4 install + R3 entity hooks landed S12-A; the 17-handler render-phase hooks DEFERRED (§4 render-fragment) |
| `MixinLevelRenderer_BeforeIris` | TARGET-GONE | **DROPPED** | Iris/`renderLevel` `"translucent"` constant GONE; no Iris-26.2 port |
| `MixinLevelRenderer_ForceMainThreadRebuild` | NEEDS-RETARGET | **LANDED** | `@ModifyVariable` → `rebuildSync` local in `compileSections` (S13 anchor-verify; WrapOp fallback) |
| `MixinLevelRenderer_Optional` | NEEDS-RETARGET | **DEFERRED-stub** | all 4 handlers target GONE methods (`RenderType.translucent`/`setupRender`/`ShaderInstance.apply`); re-sites documented per-handler |
| `MixinMinecraft_Render` | PORTS-CLEAN | **LANDED** | `shouldEntityAppearGlowing` unchanged |
| `MixinMultiBufferSourceBufferSource` | TARGET-GONE | **DROPPED** | `MultiBufferSource` class GONE; mirror-cull re-sites to the retargeted `MixinGlStateManager._enableCull` cancel (landed) |
| `MixinRenderSection` | NEEDS-RETARGET | **LANDED** | `reset()` now public; `index` `public final`+`@Mutable` |
| `MixinRenderSystem_Clipping` | TARGET-GONE | **DROPPED** | `RenderSystem.setShader` GONE — FrontClipping uniform redesign |
| `MixinRenderSystem_Fog` | NEEDS-RETARGET | **LANDED (target→FogRenderer)** | `setShaderFogStart/End` GONE → `FogRenderer.updateBuffer(FogData)` HEAD scaling `renderDistance*`/`environmental*` |
| `MixinScreenEffectRenderer` | NEEDS-RETARGET | **LANDED** | `renderTex` GONE → `submitBlockSprite(...)` HEAD-cancel (4th param `SubmitNodeCollector`, verified) |
| `MixinSectionRenderDispatcher` | NEEDS-RETARGET | **LANDED** | `<init>` redirect of `renderBuffers.sectionBufferPool()` survives (new ctor descriptor) |
| `MixinShaderInstanceForIris` | TARGET-GONE | **DROPPED** | `ShaderInstance` GONE |

### 1.8 `render/{framebuffer,isometric,optimization,shader}/` (api-map §8)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `MixinMainTarget` | TARGET-GONE | **DROPPED** | replaced by mod's shipped stencil chain (`GlConstMixin`/`RenderTargetMixin`) |
| `MixinRenderTarget` | TARGET-GONE | **DROPPED** | same replacement chain (global format swap) |
| `MixinGameRenderer_Isometric` | NEEDS-RETARGET | **DEFERRED-stub** | `getProjectionMatrix` GONE → Camera `setupOrtho`/`cameraState.projectionMatrix` re-anchor (optional isometric-debug, S13) |
| `IEChunkCompileTask` | NEEDS-RETARGET | **LANDED** | `isCancelled` moved to `RenderSection.SectionTask` (accessor retargets there) |
| `MixinFrustum` (optimization) | NEEDS-RETARGET | **LANDED** | `cubeInFrustum` now private/`(DDDDDD)I` → retarget to boolean `isVisible(AABB)` wrapper; `calculateFrustum` 1st param `Matrix4fc` |
| `MixinLevelRenderer_Clouds` | PORTS-CLEAN | **LANDED (no-op stub)** | IP ships fully-commented "TODO re-implement" |
| `MixinSectionBufferBuilderPack` | NEEDS-RETARGET | **LANDED (R13c)** | `lambda$new$0` + `ChunkSectionLayer.bufferSize()` — the ONE compiled-lambda anchor CONSUMED this stage (§2) |
| `MixinCompiledShader` | TARGET-GONE | **DROPPED** | `CompiledShader` GONE — FrontClipping shader-source redesign |
| `MixinGameRenderer_Shaders` | PORTS-CLEAN | **LANDED** | empty body |
| `MixinShaderInstance` | TARGET-GONE | **DROPPED** | `CompiledShaderProgram`/`Uniform` GONE (same GONE-`blaze3d.shaders` family as the §8.2 `IEShader` duck) |

### 1.9 `sound/` + `sync/` (api-map §9)

| IP mixin | verdict | disposition | Note |
|---|---|---|---|
| `MixinClientLevel_Sound` | PORTS-CLEAN | **LANDED** | `@IPVanillaCopy portal_playSound` renames `getMainCamera()`→`mainCamera()`, `Camera.getPosition()`→`position()`. **PHASE gameplay/event** |
| `IEBlockStatePredictionHandler` | PORTS-CLEAN | **LANDED** | verbatim (`currentSequenceNr`) |
| `MixinBlockStatePredictionHandler` | PORTS-CLEAN | **LANDED** | `startPredicting()` @RETURN, verbatim |
| `MixinClientboundPlayerPositionPacket` | TARGET-GONE | **RETIRED (superseded)** | final record + composite `STREAM_CODEC`; the dim-stamp read/write already rides the S7 common `position_sync.MixinPlayerPositionLookS2CPacket` codec-wrap (both directions). Not landed |
| `MixinClientPacketListener` | NEEDS-RETARGET | **LANDED** | see §1.11 |
| `MixinMinecraft_RedirectedPacket` | PORTS-CLEAN | **LANDED** (Slice B/C) | R7 §A step 3 — see §5 |
| `MixinReceivingLevelScreen` | TARGET-GONE | **RETIRED** | `ReceivingLevelScreen` removed (role → `LevelLoadingScreen`); IP body empty. Not landed |
| `MixinServerBoundMovePlayerPacket` | NEEDS-RETARGET | **LANDED** | base `<init>` @RETURN, ctor descriptor +2 flags `(DDDFFZZZZ)V`; client-side dim STAMP |
| `MixinServerboundMovePlayerPacketPos` | PORTS-CLEAN | **LANDED** | `Pos.write` @RETURN, `@Environment CLIENT`. WRITE append; DECODE = S7 common read mixin |
| `MixinServerBoundMovePlayerPacketPosRot` | PORTS-CLEAN | **LANDED** | `PosRot.write` @RETURN, `@Environment CLIENT` |
| `MixinServerboundMovePlayerPacketRot` | PORTS-CLEAN | **LANDED** | `Rot.write` @RETURN, `@Environment CLIENT` |
| `MixinServerboundMovePlayerPacketStatusOnly` | PORTS-CLEAN | **LANDED** | `StatusOnly.write` @RETURN, no `@Environment` (IP parity) |

### 1.10 R13i re-home (derived from MixinMinecraft ⑥)

| Mixin | disposition | Note |
|---|---|---|
| `render/MixinGameRenderState` *(NEW)* | **LANDED** | R13i — see §3. Not an inventory row; it is IP's ⑥ handler re-anchored off the GONE static `Minecraft.useShaderTransparency` onto instance `GameRenderState.useShaderTransparency()` |

### 1.9-detail `MixinMinecraft` retarget (api-map §1)

- **Ducks re-expressed** (fields moved `Minecraft`→`GameRenderer`): `ip_setFrameBuffer`/`ip_setRenderBuffers`
  route through `MOD:GameRendererAccessorMixin` setters (F12 loader-seam, same pattern as `FogRendererContext`;
  `mainRenderTarget` `26.2:GameRenderer.java:104`, `renderBuffers` `:103`). `ip_getCurrentScreen` →
  `this.gui.screen()` (`Gui.java:218`). `ip_setWorldRenderer` → `levelRenderer` (stays on `Minecraft`,
  `public final`→`@Mutable`). `ip_getRunningThread` → `gameThread` (unchanged). `getProfiler()` GONE →
  `Profiler.get()` (static).
- **⑥ dropped here** (R13i) → `render/MixinGameRenderState`. **⑦ dropped here** (`addInitialScreens` →
  `Gui`) → `MixinGui` (deferred).
- **`onAfterClientTick` runs `manageTeleportation(true)` at TICK time** (distinct from the render-time
  `manageTeleportation(false)` at the `MinecraftFramePumpMixin` pre-update pump, §4).
- **2-arg `updateLevelInEngines` retarget (pinned corpus trap — verifier-1 MAJOR #2, fixed):** `onSetWorld`
  targets the 2-arg `updateLevelInEngines(Lnet/…/ClientLevel;Z)V` (added `boolean stopSound`), NOT the 1-arg.
  IP 1.21.3 had ONE overload; 26.2 split it. `setLevel` (`:2061`) + `clearClientLevel` (`:2177`) route through
  the 1-arg wrapper (`:2191`) which delegates to the 2-arg (`:2192,:2195`), but **`Minecraft.disconnect`/kick/
  server-stop teardown calls the 2-arg DIRECTLY** (`updateLevelInEngines(null, stopSound)`, `:2146`),
  bypassing the wrapper. The 2-arg is the single funnel for all three paths (each fires it once → no
  double-fire), so `CLIENT_CLEANUP`/`CLIENT_EXIT`/`ClientWorldLoader.cleanUp` now fire on kick too. The mod's
  own live substrate `MOD:MinecraftMixin` hooks the same 2-arg descriptor for exactly this reason. IP LOGIC
  UNCHANGED — pure 26.2 translation slip corrected.
- `testMixinExtra` (`run()` `Thread.currentThread()` WrapOperation sanity probe) kept verbatim — 26.2 `run()`
  has TWO `currentThread()` sites, ordinal-less WrapOperation matches both (harmless).

### 1.10-detail `MixinMultiPlayerGameMode` retarget (api-map §4)

⚠L `redirectPlayerLevel1` (`startDestroyBlock`): the `LocalPlayer.level()` invoke is in a SYNTHETIC lambda
(`26.2:…:188→194`; IP's `method_41930` dead). `method="startDestroyBlock"` COMPILES; the inner lambda
synthetic name must be re-derived from the compiled 26.2 jar at S13 registration (R13c) or the `@Redirect`
won't resolve the invoke. `redirectPlayerLevel2` (`continueDestroyBlock` `:256`) is DIRECT (no ⚠L).
`redirectNewUseOnContext` builds the switched-level context via the `IEUseOnContext` ctor-`@Invoker`
(protected 5-arg ctor). The 3 send `@ModifyArg`s use the surviving `STREAM_CODEC`s.

### 1.11-detail `MixinClientPacketListener` retarget (api-map §9)

- **`ensureRunningOnSameThread` landmark** (all 4 `shift=AFTER` anchors): 3rd param
  `BlockableEventLoop`→`PacketProcessor` (`26.2:PacketUtils.java:21`; `minecraft.packetProcessor()`).
- **`handleMovePlayer`**: `getX/getY/getZ` GONE (record) → `packet.change().position()`; the duck cast needs
  the `(Object)` bridge (final record).
- **`handleSetTime`**: `getGameTime()`→record `gameTime()`; `ClientLevel.setGameTime`→`setTimeFromServer`.
- **`ResourceKey.location()`→`identifier()`**; **`ChunkPos`** record → `pos().x`→`pos().x()`.
- **Dead-shadow pruning**: IP's `registryAccess`/`applyLightData`/`enableChunkLight` shadows served only the
  commented-out `redirectQueueLightUpdate` (`applyLightData` also gained a trailing `boolean` on 26.2) →
  pruned to avoid a dead signature mismatch. The commented block is retained.
- **Two probe-caught slips fixed pre-gate:** the `ClientboundPlayerPositionPacket` final-record cast
  (→`(Object)` bridge) and `ChunkPos.x`→`x()`.

---

## 2. R13c — synthetic-lambda anchors re-derived from the COMPILED 26.2 jar

**Verification method.** `javap -p -classpath <JAR> <class>` on the loom Mojang-mapped merged jar
`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`; cross-checked against the NeoForge official jar
(`…/26.2/neoforge/26.2.0.7-beta/minecraft-merged-official.jar`). Both jars carry Mojang-official method
names (`addMainPass`/`addSkyPass`/`addWeatherPass`, not intermediary `method_62xxx`), so the compiled
synthetic names are `lambda$<enclosingMethod>$<index>` — NOT the 1.21.3 `method_62214/62216/62218/60896`.

| IP 1.21.3 anchor (dead) | plan line | 26.2 compiled synthetic (verified) | enclosing method | evidence (`javap`) | consumer this stage |
|---|---|---|---|---|---|
| `method_62214` (addMainPass lambda) | :391 | **`lambda$addMainPass$0`** | `addMainPass(...)` | `private void lambda$addMainPass$0(GpuBufferSlice, LevelRenderState, ProfilerFiller, ChunkSectionsToRender, ResourceHandle, PreparedFrame, ResourceHandle, ResourceHandle, ResourceHandle, ResourceHandle)` — **FIVE `ResourceHandle`, with `PreparedFrame` (`FeatureRenderDispatcher$PreparedFrame`) as the 6th param** (NOT `ResourceHandle×4`-then-`PreparedFrame`; re-verified via javap on the loom merged jar — verifier-1 MINOR #3 correction) | DEFERRED §4 (terrain/entity brackets) |
| `method_62218` (clear lambda) | :197 | **`lambda$render$0`** | `render(...)` | `private void lambda$render$0(org.joml.Vector4f)` — captures `fogColor`; sole `lambda$render$` | DEFERRED §4 (clear redirect) |
| `method_62216` (weather lambda) | :474 | **`lambda$addWeatherPass$0`** | `addWeatherPass(...)` | `private void lambda$addWeatherPass$0(GpuBufferSlice, int)` | DEFERRED §4 (weather clip) |
| addSkyPass `FramePass.executes` lambda | :329 | **`lambda$addSkyPass$0`** | `addSkyPass(...)` | `private void lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)` | DEFERRED §4 (sky skip/mirror-cull) |
| `method_60896` (SectionBufferBuilderPack field-init lambda) | — | **`lambda$new$0`** | `<init>` (`Util.makeEnumMap(ChunkSectionLayer.class, layer -> new ByteBufferBuilder(layer.bufferSize()))`) | `private static ByteBufferBuilder lambda$new$0(ChunkSectionLayer)` (both fabric+neoforge) | **`MixinSectionBufferBuilderPack` (LANDED)** — redirect `ChunkSectionLayer.bufferSize()` |

Only `lambda$new$0` is CONSUMED by a landed mixin this stage; the four `LevelRenderer` lambdas anchor the
DEFERRED render-phase hooks (§4) — the table pins their verified names so S13 wires them without re-deriving.
The `MixinMultiPlayerGameMode.startDestroyBlock` prediction lambda (§1.10 ⚠L) is the sixth R13c anchor and
is re-derived at S13 registration.

---

## 3. R13i / R13k records (do-not-drop)

### 3.1 R13i — fabulous/transparency-suppression OVERRIDE re-anchored

**File:** NEW `MOD-qouteall:mixin/client/render/MixinGameRenderState.java` (held-UNREGISTERED).

IP's HEAD-cancel that forces the fabulous transparency path OFF while a portal renders lived on the STATIC
`Minecraft.useShaderTransparency()` (`IP:MixinMinecraft.java:178-183`). That method is GONE; the flag moved
to the INSTANCE method `GameRenderState.useShaderTransparency()` (`26.2:state/GameRenderState.java:17-19`,
G10). The override re-anchors onto a new `@Mixin(GameRenderState.class)` — HEAD-cancel,
`CallbackInfoReturnable<Boolean>`, **non-static**, `setReturnValue(false)` when `WorldRenderInfo.isRendering()`.
Because IP carried this inside its monolithic multiworld `MixinMinecraft` but the TARGET moved to a render
state, the 26.2 re-expression is a standalone render-slice mixin (the multiworld `MixinMinecraft` port omits
it). This is the "easy to silently drop" item (API_RISKS R13i / CUTOVER_SPEC §6.4) — verified PRESENT + correct.

### 3.2 R13k — camera pull-model gates + view-rotation ⑪

**Files:** NEW `MixinCamera.java`; RECONCILED `MixinGameRenderer.java`.

- **`MixinCamera` (R13k core).** `Camera.setup(...)` GONE → `update(DeltaTracker)`; `adjustCameraPos`
  re-anchors at `update` **after the `alignWithEntity` INVOKE, before `prepareCullFrustum`** (the frustum
  snapshots `this.position`, `26.2:Camera.java:106`). `getFluidInCamera`/`isDetached` HEAD-cancels port clean.
  Field `level` retyped `BlockGetter`→`Level`. The **initialized-flag** gate (else `getFluidInCamera` returns
  `NONE`) is handled by the mod's existing `CameraInvokerMixin` (S13 wiring) — documented, not duplicated.
- **R13k view-rotation ⑪ (`MixinGameRenderer.onExtractEnded`).** IP wrapped `Matrix4f.rotation(Quaternionfc)`
  inside 1.21.3 `renderLevel`. On 26.2 that rotation moved into `Camera.getViewRotationMatrix`, which is
  **dirty-flag cached** (`Camera.java:385-389`) and copied to `cameraState.viewRotationMatrix` at extract
  (`:135`). Per R13k: **NEVER wrap the cached getter** — the port injects at `GameRenderer.extract` RETURN and
  post-processes `gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix` via
  `TransformationManager.processTransformation(mainCamera, …)`, so the downstream R3 clip / terrain consumers
  (which READ `cameraState.viewRotationMatrix` — `PerEntityClipBracket`, `MixinLevelRenderer_CrossPortalEntity`)
  see the transformed rotation exactly as IP's downstream saw the wrapped matrix. (Grep confirmed
  `processTransformation` had ZERO callers in the held tree before this = R13k was unwired.) Runtime
  finalization (per-pass timing, the `viewRotationMatrix` ↔ pushed `modelViewMatrix` equivalence) is a named
  S13 rung-1 check (S12A-renderers.md §4/§6).

---

## 4. A2 view-bob trio + C5 decision + the S13 `MainProjectionBobMixin` retirement handoff

**Files:** NEW `MOD-qouteall:mixin/client/render/MixinGameRenderer.java` (held-UNREGISTERED);
EDIT `MOD:mixin/client/MinecraftFramePumpMixin.java` (S3 host; inert scaffold, comment-only).

**The trio (VERBATIM IP logic, `IP:mixin/client/render/MixinGameRenderer.java:200-253`).** The one piece of
IP's 11-handler frame-driver `MixinGameRenderer` with no mod-owned re-expression. Every other IP handler is
already re-expressed onto MOD-owned code (render-HEAD pre-render pump → `MOD:MinecraftFramePumpMixin`, S3;
`render`/`renderLevel` driver hooks → `MOD-qouteall:MyGameRenderer`, S11-B), so porting the full IP mixin
would duplicate that logic AND pull S13-closure imports across the gate. Five handlers ported: 2× `@Inject`
bracketing `renderItemInHand` (HEAD/RETURN → the `portal_isRenderingHand` flag) + 3× `@ModifyArg` on the
single `PoseStack.translate(FFF)` inside `bobView`, each scaling the arg by
`RenderStates.getViewBobbingOffsetMultiplier()` UNLESS the hand is rendering.

| IP 1.21.3 | 26.2 | Evidence |
|---|---|---|
| `bobView(PoseStack, F)` | `bobView(CameraRenderState, PoseStack)` — body still has exactly one `translate(FFF)` (`26.2:GameRenderer.java:322,326-330`; the two following calls are `mulPose`, not `translate`) | `@ModifyArg method="bobView"` index 0/1/2 port clean |
| `renderItemInHand(Camera, F, Matrix4f)` | `renderItemInHand(CameraRenderState, float, Matrix4fc)` (`:336`; calls `bobView` at `:349`) | callback params `(CameraRenderState, float, Matrix4fc, CallbackInfo)`; the hand's own bob is bracketed `portal_isRenderingHand=true` → left UNSCALED, exactly IP's intent |
| `private static boolean portal_isRenderingHand` (no `@Unique`) | same field `+@Unique` | mixin-hygiene addition (migration convention), NOT an IP-logic change; IP's `portal_` name kept |

`RenderStates.getViewBobbingOffsetMultiplier()` present in the held tree (S11-A, `RenderStates.java:208` =
`viewBobFactor * PortalRendering.getExtraModelViewScaling()`, gated by `IPGlobal.viewBobbingReduce`).

**The IEGameRenderer-duck RECONCILIATION (render-slice extension of the bob file).** Bob-only left two gaps:
(a) the `IEGameRenderer` ducks `ip_setCamera`/`ip_setLightmapTextureManager` are LIVE-called by
`MyGameRenderer.switchAndRenderTheWorld` (`:235,247,297,302`), `RenderStates.onTotalRenderEnd:233`,
`ClientTeleportationManager:484` via `(IEGameRenderer) client.gameRenderer` — with no impl they CCE at S13;
and (b) R13k ⑪. The bob mixin was EXTENDED to `implements IEGameRenderer` + four ducks
(`ip_setLightmapTextureManager`→`lightmap` field retyped to 26.2 `Lightmap`; `ip_setCamera`→`mainCamera`;
`ip_getDoRenderHand`→`true` (field GONE, consumer dropped S11-B); `ip_setIsRenderingPanorama`→
`mainCamera.enable/disablePanoramicMode`) + the R13k ⑪ hook. These additions pull NO S13-closure imports
(only `IEGameRenderer`, `TransformationManager`, `RenderStates`, 26.2 types) — probe ZERO errors on the file.

**Checkpoint C5 (2026-07-15, BINDING) + EXCLUSIVITY_LEDGER row A7.** The live block-era
`MOD:MainProjectionBobMixin` `@Redirect`s the world-projection `bobView`/`bobHurt` INVOKEs in `renderLevel`
(`26.2:GameRenderer.java:538-539`) to NO-OPs (bob killed entirely). IP instead SCALES the args inside
`bobView`. These operate at different levels and would conflict if both ran; they never do — the exclusivity
is load-time. **C5 decided IP behavior wins VERBATIM; the visible change (bob returns to full strength away
from portals) is ACCEPTED, no deviation entry.** `MainProjectionBobMixin` is NOT deleted now — its retirement
(gate/unregister) executes at the S13 exclusivity flip **in the SAME commit** that registers the held
`MixinGameRenderer`. Flag-OFF today only `MainProjectionBobMixin` runs (mod behavior); flag-ON at S13 only the
held `MixinGameRenderer` runs (IP behavior) — the interaction never arises at runtime.

**S13 retirement handoff.** At S13, in one commit: (1) register the held `MixinGameRenderer` into the flag-ON
client set; (2) retire `MOD:MainProjectionBobMixin` (row A7); (3) both-states check — flag-OFF bob killed
(mod), flag-ON bob distance-scaled (IP). **Because it is a live-tested entity path, C4 requires bringing up
the A/B clip switch whenever the user live-tests entities through portals (S13/S15/S17/S18).**

**The S3 inert dispatch branch (`MinecraftFramePumpMixin`).** The S3 host gains its still-inert S13
flag-dispatch scaffold **COMMENT-ONLY** (per S03-frame-anchor.md §6 + EXCLUSIVITY_LEDGER §4 row 1): a live
branch is impossible pre-S13 because (a) the `entityPortals` flag is created at S13 (no such field exists in
shipped `com.warwa` today), and (b) the flag-ON IP chain (`RenderStates.updatePreRenderInfo` →
`StableClientTimer.update` → `ClientPortalAnimationManagement.update` →
`ClientTeleportationManager.manageTeleportation(false)` → `MyRenderHelper.earlyRemoteUpload`) references HELD
`qouteall.*` classes EXCLUDED from the shipping build. The block-era pump runs UNCONDITIONALLY at S12-B
(runtime byte-identical to S3). `git diff`-verified: additions are comment lines only; shipping stayed GREEN.

---

## 5. R7 §A — the `scheduleIfPossible` ordering correction (SPIKE-R7-SSA record)

**Two files, both halves of the order-faithful packet redirection:**
- COMMON: `MOD-qouteall:mixin/common/networking/MixinClientboundCustomPayloadPacket.java` (R7 §A step 2).
- CLIENT: NEW `MOD-qouteall:mixin/client/sync/MixinMinecraft_RedirectedPacket.java` (R7 §A step 3).

**R7 §A step 2 — the correction APPLIED HERE (verifier-1 MAJOR #1, fixed).** The common mixin was committed
**VERBATIM-STOCK at S10.2 (1d2d834), MISSING** the S07-network.md §2-mandated correction (SPIKE-R7 caught it
at S12-B). Stock IP called `redirectPayload.handle(...)` + `ci.cancel()` on BOTH the netty and main passes,
with no `isSameThread` guard. On the netty pass that drops into `PacketRedirectionClient.handleRedirectedPacket`'s
`!isSameThread` branch → `minecraft.execute(resubmit)` — the exact EXECUTE shape SPIKE-R7 EMPIRICALLY PROVED
reorders totally (all later-sent vanilla packets processed before all earlier redirected ones every frame,
because `Minecraft.processQueuedPackets` drains `scheduledPacketProcessing` BEFORE `scheduledExecutables`,
`26.2:Minecraft.java:1169-1172`). The mandated fix (now landed on `onHandle`):

```
if (payload instanceof PacketRedirection.Payload redirectPayload) {
    Minecraft mc = Minecraft.getInstance();
    if (!mc.packetProcessor().isSameThread()) {                 // netty pass
        mc.packetProcessor().scheduleIfPossible(
            listener, (Packet<ClientCommonPacketListener>) (Object) this);   // re-queue the OUTER packet
    } else if (listener instanceof ClientGamePacketListener cgpl) {          // main pass
        redirectPayload.handle(cgpl);                            // handle inline
    }
    ci.cancel();                                                // both passes
}
```

Re-queuing the OUTER packet through the vanilla packet processor drains it in the same
`scheduledPacketProcessing` pass as vanilla packets → send order preserved. Verified against mc262-ref:
`Minecraft.packetProcessor()` (`:2922`) returns `PacketProcessor` with `isSameThread()` (`:22`) and
`<T extends PacketListener> void scheduleIfPossible(T, Packet<T>)` (`:26`) — signature matches the block
exactly. **Compounding false-doc claims corrected:** both the pre-fix S12B-behavior.md §2 assertion and the
`MixinMinecraft_RedirectedPacket` javadoc had claimed the step-2 re-queue was "already landed" (grep showed
ZERO `scheduleIfPossible` implementations at that point); both now state the common mixin was committed
verbatim-stock at S10.2 MISSING the correction, applied HERE at S12-B.

**R7 §A step 3 — client mixin `MixinMinecraft_RedirectedPacket`.** Narrows the redirection to ONLY
`Minecraft.execute` tasks submitted DURING redirected handling: `wrapRunnable` HEAD (cancellable, CIR) tags
such a task to run inside the redirected world via `ClientWorldLoader.withSwitchedWorldFailSoft`, and the
`@IPVanillaCopy scheduleExecutables()` override keeps the redirected handling from being delayed. **PORTS-CLEAN
on 26.2, re-diffed this stage:** `Minecraft extends ReentrantBlockableEventLoop<Runnable>` (`:261`);
`public Runnable wrapRunnable(Runnable)` (`:2668`, `@Inject` descriptor unchanged). The `@IPVanillaCopy`
`scheduleExecutables()` body reproduces `this.runningTask() || !this.isSameThread()` EXACTLY (26.2
`ReentrantBlockableEventLoop.scheduleExecutables()` = `runningTask() || super.scheduleExecutables()`,
base = `!isSameThread()`). **API-map slip fixed (probe-caught):** `ReentrantBlockableEventLoop`'s ctor gained
`boolean propagatesCrashes` — the fake shadow-extend ctor `super(string)`→`super(string, bl)` (never invoked;
bytecode merges into `Minecraft`; arg values irrelevant).

---

## 6. `IPModInfoChecking` (F12 loader-seam) — resolves the mission-named `PortalRenderer` forward-refs

**Files:** NEW `MOD-qouteall:compat/IPModInfoChecking.java` (held); NEW compileOnly stub
`common/src/fabricStubs/.../event/lifecycle/v1/ServerLifecycleEvents.java`.

Resolves `IP:PortalRenderer.java:20` import + `:420` `checkShaderpack()` call — the two S12-B-owned
`PortalRenderer` forward-refs the mission flagged (`IPModInfoChecking` "THIS stage").

**Net held-file seam hunks on IPModInfoChecking = ZERO (it lands byte-verbatim).** The plan's "`:1016`
F12-seam-hunks" language is superseded by S10-A's STUB-not-SEAM decision. Per S10-A §2.1/§2.2 the
`@Environment`/`EnvType` and the `FabricLoader`/`ModContainer` family are resolved by the `:common`-only
compileOnly **fabricStubs**, held file kept byte-verbatim. IPModInfoChecking's fabric imports (`:4-8`) follow
that pattern: `net.fabricmc.api.{EnvType,Environment}` + `net.fabricmc.loader.api.{FabricLoader,ModContainer}`
resolve against EXISTING fabricStubs (zero edits). The one gap —
`net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents` (used by `initDedicatedServer` →
`SERVER_STARTED.register(...)`) — had no stub, so a new fabricStubs **shell** was added (only `SERVER_STARTED`
is consumed by the held tree, grep-verified; same "shell as far as the held tree consumes THIS type"
discipline as the sibling `ServerTickEvents` stub). Adding a fabricStubs file needs no `build.gradle` edit
(the source set globs the dir); removed at S20.

**The ONE in-file hunk = a 26.2 API-map translation comment, not an IP-logic change:** `net.minecraft.Util`
→ `net.minecraft.util.Util` (package move); `Util.backgroundExecutor()` now returns
`net.minecraft.TracingExecutor` which `implements Executor` and has `execute(Runnable)`
(`26.2:TracingExecutor.java:9,33`), so the call site is unchanged (matches `MOD-qouteall:McHelper.java:10`).
All other qouteall deps are ≤S5 (`O_O`, `CHelper`, `IPGlobal`, `IPMcHelper`, `McHelper`, `IrisInterface`,
`ServerTaskList`, `IPConfig`, `Helper`, `MyTaskList`) — probe-resolved. `IPConfig`'s own autoconfig debt is
pre-existing (S13), not introduced here.

**Task-4 VERIFY-ONLY pass (no code change):** `MixinGui_Overlay` + `CustomTextOverlay` (held S7) re-verified
against the 26.2 GUI surface — **BOTH clean, no slip.** `Gui.extractRenderState(DeltaTracker,boolean,boolean)`
(`:148`); the `@Inject RETURN CAPTURE_FAILHARD` locals `(ProfilerFiller, int xMouse, int yMouse,
GuiGraphicsExtractor)` are exactly the method-body locals in slot order (block-scoped `CrashReport`/`Zone`
not in the LVT at RETURN). `CustomTextOverlay.render(GuiGraphicsExtractor, DeltaTracker)`: `pose()` →
`Matrix3x2fStack` (JOML push/pop), `centeredText`/`text`/`Font.split`/`Profiler.get()` all present + typed.

---

## 7. THE HARD GATE VERDICT — the S13 entry ticket

**AUTHORITATIVE builds** (`--no-daemon`, per `multiloader-common.gradle`; logs in the session scratchpad
`s12b_fix_shipping.log` / `s12b_fix_probe.log` / `s12b_fix_test.log`).

| Build | Command | Result |
|---|---|---|
| Shipping build | `:common:compileJava` (no flag) | **BUILD SUCCESSFUL** — all edited/new files are held/excluded (`:common:compileJava` UP-TO-DATE); ZERO shipping impact; live block-era render byte-untouched |
| `:common:test` | `:common:test` | **BUILD SUCCESSFUL** |
| Probe (HARD GATE) | `:common:compileJava -Pip_scc_closed=true` | **165 javac errors** (`grep : error:` = 166; the +1 = the documented Gradle problems-report phantom, S11B §7). ZERO signature-category errors (no incompatible-types / does-not-override / private-access / cannot-be-applied / ambiguous). ZERO errors reference the three verifier-fixed files; both edited held mixins compile clean |

**Trajectory:** S12-A baseline 161 javac → S12-B rose as ~62 client/common mixins landed (each carrying its
own S13-closure forward-refs) → 3 category-(c) slips fixed (§8) → **165**. The gate is the *per-error
closure-set membership*, not the integer.

**HARD GATE HELD.** Every one of the 165 probe errors classifies 100% into the S13 closure set:

| Bucket | Symbols / packages | Resolves at |
|---|---|---|
| U11 autoconfig mass | `me.shedaniel.autoconfig`(+`.annotation`/`.serializer`), `ConfigEntry.*`, `AutoConfig`, `Config`/`ConfigData`/`ConfigHolder`, `GsonConfigSerializer` (bulk of `IPConfig`=65, `IPModMain`=24, `IPGlobal`) | S13 (F21 autoconfig stub) |
| commands + argument types | `qouteall.imm_ptl.core.commands`, `PortalCommand`, `ClientDebugCommand`, `PortalDebugCommands`, `AxisArgumentType`/`SubCommandArgumentType`/`TimingFunctionArgumentType` | S13 closure |
| block_manipulation | `qouteall.imm_ptl.core.block_manipulation`, `BlockManipulationClient`, `BlockManipulationServer` | S13 closure |
| U12 generation slice | `custom_portal_gen`, `BreakablePortalEntity`, `NetherPortalEntity`, `GeneralBreakablePortal`, `FastBlockAccess`, `BlockTraverse`, `CustomPortalGenManager`, `PortalGenInfo` | S13 (U12 slice) |
| wand / debug / misc shells | `PortalWandInteraction` (wand), `qouteall.imm_ptl.core.debug`/`DebugUtil`, `GcMonitor`/`DubiousThings` (`miscellaneous/`) | S13 closure |
| **ratified amendment** | `ShaderCodeTransformation` (`IPModMainClient:25,:77`) | S13 (SHELL — see below) |
| documented residue | `addTask`/`completeTask` (`ImmPtlNetworkConfig`) | `:fabric` (Fabric interface-injection — environment-justified, S10A §4) |

**No probe error references anything outside this set, and none is a translation slip.**

### Ratified gate-set AMENDMENT — `render/ShaderCodeTransformation` (import-graph-justified; per D4.2 NOTE)

Not a slip — an import-graph-justified miss OUTSIDE the plan's enumerated S13 closure set, ratified by the
parent and committed with this stage. **Import chain:** `IPModMainClient` (the U11 client-init closure hub,
committed S10.1) imports `qouteall.imm_ptl.core.render.ShaderCodeTransformation` (`IPModMainClient.java:25`)
and calls `ShaderCodeTransformation.init()` (`:77`) exactly as IP does; the client-init hub's own import graph
therefore pulls it into the S13 closure. Per the D4.2 NOTE the set is AMENDED to include it. **Plan amended:**
`EXECUTION_PLAN.md` gained the S12(b) "S12-B GATE-SET AMENDMENT" note (`:1036-1044`) + the S13(a) render-shell
closure line (`:1187-1189`).

**S13-GREEN BLOCKER (recorded LOUDLY, rides the S13 entry ticket):** IP's `ShaderCodeTransformation` imports
the GONE 26.2 type `com.mojang.blaze3d.shaders.CompiledShader` (`api-map/mixin-client.md §8` `MixinCompiledShader`
row — the whole `CompiledShader`/`CompiledShaderProgram` GLSL-compile stack is TARGET-GONE, re-sites to the
`ShaderManager` source layer under the FrontClipping redesign). It is therefore **NOT verbatim-portable.**
**S13 MUST** do one of: (1) land `ShaderCodeTransformation` as a **SHELL** with the shader-transform internals
commented / FrontClipping-deferred (mirroring the dropped `MixinCompiledShader`/`MixinShaderInstance`/
`MixinRenderSystem_Clipping`/`IEShader` precedent — same redesign owner), OR (2) gate/comment the
`IPModMainClient.init()` call to `ShaderCodeTransformation.init()`. Left unresolved, `IPModMainClient` stays
RED at S13 and there is no first green build. **Porting `ShaderCodeTransformation` early is FORBIDDEN this
stage** (S13 closure class); it is documented, not authored.

**This §7 census IS the S13 entry ticket:** every red at closure is accounted for and owned by a downstream
stage; S13 opens against exactly this set.

---

## 8. FixGaps / AW-AT / IpHeldPaths record

### 8.1 Category-(c) translation slips fixed (blocked a clean gate) — full record in `S12B-gate-closure.md`

| Slip | Fix | IP logic |
|---|---|---|
| `ClientWorldLoader.java:545,:610` — `Map<String,MapItemSavedData>` locals vs 26.2 `ClientLevel.mapData` = `Map<MapId,…>` (key `String`→`MapId`, `26.2:ClientLevel.java:160`); the S12-B accessor `IEClientLevel_Accessor` was already CORRECT (returns/accepts `Map<MapId,…>`), the S10 locals were the slip → 2× `incompatible types` | retyped the local to `Map<MapId,MapItemSavedData>` + added the `net.minecraft.world.level.saveddata.maps.MapId` import | UNCHANGED — same "all worlds share the map-data map" hand-off; only the generic key moves to the type the accessor already speaks |
| `ducks/IEShader.java:3` — dead `import com.mojang.blaze3d.shaders.Uniform;` (a GONE 26.2 type; **vestigial even in IP 1.21.3** — the interface body `int ip_getClippingEquationUniformLocation();` never used it) | deleted only the dead `Uniform` import; kept the file held-inert (grep: no live Java references it — only `FrontClipping.java:24,:313` comments); documented the GONE-type origin + FrontClipping ownership in a header comment | interface body kept VERBATIM from IP; stays HELD until the FrontClipping redesign revives/retires it |

Deferred alternative deliberately NOT taken: dropping `IEShader` would require a non-carve-in `IpHeldPaths`
edit (removing line 112), restricted to genuine carve-in holds — the parent ruling authorized the delete-import
alternative, which was chosen.

### 8.2 AW / AT

**ZERO AW/AT pairs across the entire stage.** Every consumed member is public, a public getter, or reached
via an existing accessor / a constructor `@Invoker` (`IEUseOnContext`). No `.accesswidener` edit, no NeoForge-AT
edit.

### 8.3 IpHeldPaths / build wiring

- **No `IpHeldPaths.groovy` edit.** Every held port lives under wholesale-held trees
  (`qouteall/imm_ptl/core/mixin/**`, `qouteall/imm_ptl/core/compat/**`, `qouteall/imm_ptl/core/ducks/**`). The
  `IEShader` disposition (§8.1) deliberately avoided the non-carve-in held-list edit that dropping the file
  would have required.
- **No `buildSrc/build.gradle` edit. No `mixins.json` edit** — all 62-set mixins compile as ordinary annotated
  Java; registered flag-ON into the S13 client-mixin set (EXCLUSIVITY_LEDGER §6.2), `MainProjectionBobMixin`
  retires the same commit (A7).
- The `ServerLifecycleEvents` fabricStubs shell (§6) is a globbed compileOnly stub — no build wiring; removed
  at S20.

### 8.4 The single sanctioned `com.warwa` touch

`MOD:mixin/client/MinecraftFramePumpMixin.java` — the ONLY `com.warwa` diff vs HEAD is **comment-only,
runtime byte-identical**: the plan-authorized S3-anchor inert IP-dispatch scaffold (EXECUTION_PLAN S12 /
EXCLUSIVITY_LEDGER §4 row 1). Parent-ratified as the single sanctioned exception to the literal "zero
com.warwa" requirement. NOT a defect.

### 8.5 Working-tree touch summary (S12-B, all slices)

- ~49 NEW held files under `mixin/client/**` + `compat/IPModInfoChecking.java` + the `ServerLifecycleEvents`
  fabricStubs shell.
- EDIT (tracked): `ClientWorldLoader.java` (§8.1 MapId), `ducks/IEShader.java` (§8.1 Uniform),
  `mixin/common/networking/MixinClientboundCustomPayloadPacket.java` (§5 R7 step 2),
  `MOD:MinecraftFramePumpMixin.java` (§8.4 comment-only), `migration/EXECUTION_PLAN.md` (§7 amendment).
- **No S13 closure class ported early.**

---

## 9. VERIFICATION TIER

**Tier: dual adversarial verify (Fable) + fix, per the model-tier policy for hard stages (S12).**

- **Verifier-1** found THREE defects, all independently confirmed real (none a mis-flagged mandated
  translation or forward-debt) and fixed ZERO-deviation:
  1. **MAJOR #1** — R7 §A step-2 correction ABSENT (common `MixinClientboundCustomPayloadPacket` was
     verbatim-stock, MISSING `scheduleIfPossible`). FIXED per S07-network.md §2 (§5); two false "already
     landed" doc claims corrected.
  2. **MAJOR #2** — held `MixinMinecraft` cleanup hook targeted the 1-arg `updateLevelInEngines`, missing the
     kick/teardown path. FIXED by retargeting to the 2-arg descriptor (§1.9-detail).
  3. **MINOR #3** — R13c `lambda$addMainPass$0` descriptor mis-transcribed (`ResourceHandle×4, PreparedFrame`
     → actually FIVE `ResourceHandle` with `PreparedFrame` 6th). FIXED (§2), re-verified via `javap`.
- **Verifier-1 non-defect WATCH (recorded, no action):** `MixinAbstractClientPlayer` `@Shadow`s the inherited
  `Entity.setLevel(Level)` — compiles green + the merged INVOKEVIRTUAL is legal, but confirm Mixin's
  shadow-method hierarchy resolution the moment the S13 client set registers (carried in §1.1).
- **Verifier-2** found nothing requiring action before commit (all gate buckets plan-enumerated or
  ratified-amended; fidelity samples clean; shipping/test green fresh; working tree only intended edits; all
  new mixins unregistered).
- **Highest-priority categories independently re-verified CLEAN post-fix:** R13i `MixinGameRenderState` present
  + correct; no `getViewRotationMatrix` wrap (R13k extract-RETURN post-process per §3.2); no deleted live
  mixin (`MainProjectionBobMixin` + `MOD:MinecraftMixin` both present); no early port; no unjustified
  hard-gate survivor.

**S13 watch items carried forward:** (1) ⚠L `MixinMultiPlayerGameMode.redirectPlayerLevel1` +
`MixinSectionBufferBuilderPack` lambda re-derivation at registration; (2) `MixinGui` ⑦ re-enable once
`IPortalInitialScreen` lands; (3) `MixinDebugScreenOverlay` custom `DebugScreenEntry`; (4) `MixinParticleEngine`
per-particle filter + tick-skip on the live driver; (5) `MixinLivingEntity_C` interpolation broadening
live-verify; (6) `MixinAbstractClientPlayer` shadow-hierarchy resolution; (7) the DEFERRED `LevelRenderer`
render-phase hooks land with the FrontClipping vanilla-terrain-clip design + extract/render-split anchoring
(anchors pinned §2); (8) R13k ⑪ per-pass timing / pushed-modelView equivalence rung-1 check; (9) the
`ShaderCodeTransformation` SHELL-or-gate decision (§7) — REQUIRED for the first green build; (10) registration
reconciliation between the render, multiworld, and behavior slices (`IEParticle`,
`MixinMinecraft_RedirectedPacket`, `MixinGameRenderState` are shared — no double-registration).

---

## STATUS: S12 / U10 COMPLETE.

The client-mixin second half of U10 is ported held-UNREGISTERED, the three verifier-1 defects are fixed
zero-deviation, and the **HARD CLOSURE GATE HOLDS** — the final probe census classifies 100% into the S13
closure set (+ the one ratified `ShaderCodeTransformation` import-graph amendment, chain documented). This is
the S13 entry ticket. **Next stage: S13 — SCC closure + first light (USER runClient gate; bring up the A/B
clip switch per C4).**
