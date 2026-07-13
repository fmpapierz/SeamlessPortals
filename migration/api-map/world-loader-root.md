# 26.2 API Map: ClientWorldLoader + root helpers

Slice inventory: `migration/inventory/world-loader-root.md` (IP 1.21.3 root files).
26.2 ground truth: decompiled vanilla at `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings; NeoForge-patched
decompile — dist markers are `net.neoforged.api.distmarker.OnlyIn`). All 26.2 citations below are relative to
that root. Pre-verified render/pipeline facts inherited from `MIGRATION_API_MAP.md` are marked "(per
MIGRATION_API_MAP)" and were re-confirmed where this slice touches them. Verified by direct reads 2026-07-10;
corrections from the 2026-07-12 adversarial re-verification (`migration/verify/world-loader-root.md` R1-R3)
applied 2026-07-12.

Verdict counts: **GONE 14 · CHANGED 27 · SAME 101** (SAME counted as rows in §5; grouped members in one row count once).

Two global renames cascade through every row (stated once, not repeated per-row):

- **`ResourceLocation` → `Identifier`** — class renamed, same package
  (`net/minecraft/resources/Identifier.java`; `fromNamespaceAndPath` :40, `parse` :44). Every signature that
  took/returned `ResourceLocation` now uses `Identifier`.
- **`net.minecraft.Util` → `net.minecraft.util.Util`** — package move (`net/minecraft/util/Util.java`).

---

## 1. GONE (no member of that name/shape; 26.2 mechanism cited)

| IP usage (where) | Verdict | 26.2 mechanism that fills the role | Migration note |
|---|---|---|---|
| `Minecraft.mainRenderTarget` field / `getMainRenderTarget()` (IEMinecraftClient duck shadow, MixinMinecraft.java:40-72) | GONE | Render target owned by GameRenderer: `gameRenderer.mainRenderTarget()` — `GameRenderer.java:673` (per MIGRATION_API_MAP); vanilla usage `Minecraft.java:660` `RenderTarget mainRenderTarget = this.gameRenderer.mainRenderTarget();` | Drop the Minecraft duck member; go through the public `gameRenderer` field (`Minecraft.java:289` per MIGRATION_API_MAP). |
| `Minecraft.renderBuffers` field / `.renderBuffers()` (secondary-LevelRenderer ctor arg, ClientWorldLoader.java:404-409; duck shadow) | GONE | `GameRenderer.renderBuffers()` — `GameRenderer.java:184` (per MIGRATION_API_MAP); the 26.2 `LevelRenderer` pulls it itself: `this.renderBuffers = gameRenderer.renderBuffers();` `LevelRenderer.java:145` | RenderBuffers no longer passed to the LevelRenderer ctor at all — the ctor takes `GameRenderer` (see CHANGED C5). Shared-RenderBuffers-across-dims is implicit as long as secondary renderers receive the same GameRenderer. |
| `Minecraft.getProfiler()` (`Supplier<ProfilerFiller>` ClientLevel ctor arg :445-456; push/pop in tick :398-400) | GONE | Thread-local profiler `Profiler.get()` — `net/minecraft/util/profiling/Profiler.java`; vanilla pattern `Minecraft.java:1768` `ProfilerFiller profiler = Profiler.get();` | The ClientLevel ctor no longer takes a profiler supplier (see CHANGED C4). Replace `CLIENT.getProfiler().push/pop` with `Profiler.get().push/pop` (`ProfilerFiller.java:13-17`). |
| `Minecraft.setScreen(Screen)` / `Minecraft.screen` field (CHelper.openLinkConfirmScreen :97-116; duck shadow) | GONE | Moved to Gui: `Gui.setScreen(@Nullable Screen)` `net/minecraft/client/gui/Gui.java:222`; current-screen field `Gui.java:74` `private @Nullable Screen screen;`, getter `screen()` :218. Also `Minecraft.setScreenAndShow(Screen)` `Minecraft.java:2184` (sets + forces a frame). | Call `minecraft.gui.setScreen(...)` (`gui` is public final, `Minecraft.java:290`). `Gui.setScreen`'s off-thread error log is gated on `SharedConstants.IS_RUNNING_IN_IDE` (`Gui.java:223-225`) — in a production client an off-thread `setScreen` logs **nothing** and proceeds unguarded. IDE-only diagnostic; do not rely on it as a runtime off-thread tripwire. |
| `Minecraft.useShaderTransparency()` (inject HEAD cancellable, MixinMinecraft.java:178-183) | GONE | `GameRenderState.useShaderTransparency()` — `net/minecraft/client/renderer/state/GameRenderState.java:17-19` (`!levelRenderState.cameraRenderState.isPanoramicMode && optionsRenderState.improvedTransparency`); callers go through `minecraft.gameRenderer.gameRenderState().useShaderTransparency()` (`WeatherEffectRenderer.java:129`, `LevelRenderer.java:835`, `ItemFeatureRenderer.java:113`) | IP's cancellable HEAD inject must retarget `GameRenderState.useShaderTransparency`. Note it now reads extracted render-state booleans, not live options. |
| `LevelRenderer.tick()` (per-tick remote renderer ticking, ClientWorldLoader.java:119-123) | GONE | No per-tick method exists on 26.2 `LevelRenderer` (`LevelRenderer.java:95` — grep confirms no `tick()`). Its old duties moved: block-destruction progress now lives on `ClientLevel` (`ClientLevel.java:178` `destructionProgress` map, getter :435, `destroyBlockProgress` :808) and is consumed per-frame by `LevelExtractor` (`LevelExtractor.java:279`, :324). | The remote-renderer `worldRenderer.tick()` loop has **no direct 26.2 target**. The destruction-progress role is fulfilled automatically by ticking the remote `ClientLevel` (its state) + running extraction. What remote-dimension work remains renderer-side is a design-stage question — flag for the render slice. |
| `LightTexture` class — ctor `(GameRenderer, Minecraft)`, `.tick()`, `.close()` + `GameRenderer.lightTexture()` (DimensionRenderHelper.java:21-39; conflict detection ClientWorldLoader.java:127-145) | GONE | Split into three: **`Lightmap`** (`net/minecraft/client/renderer/Lightmap.java:25`; no-arg ctor :44, `getTextureView()` :52, `close()` :57, `render(LightmapRenderState)` called at `GameRenderer.java:423`), **`LightmapRenderStateExtractor`** (`net/minecraft/client/renderer/LightmapRenderStateExtractor.java:22`; ctor `(GameRenderer, Minecraft)` :30, `tick()` :35, `extract(LightmapRenderState, float)` :47), and **`LightmapRenderState`** (`net/minecraft/client/renderer/state/LightmapRenderState.java`, one per `GameRenderState`: `GameRenderState.java:11`). GameRenderer owns one of each privately (`GameRenderer.java:112-113`); texture exposed via `lightmap()`/`levelLightmap()` → `GpuTextureView` (`GameRenderer.java:661-666`); driven at tick `:258`, extract `:386`, render `:423`. | `DimensionRenderHelper`'s per-dim lightmap must become a per-dim (Lightmap + LightmapRenderStateExtractor + LightmapRenderState) triple. The extractor ctor conveniently keeps the old `(GameRenderer, Minecraft)` shape. The "helper holds the current gameRenderer light texture" identity check must compare against the private `GameRenderer.lightmap` field (accessor mixin needed — no getter for the `Lightmap` object itself, only its texture view). |
| Packet-requeue interception: `Minecraft.wrapRunnable` HEAD inject + `scheduleExecutables()`/`runningTask()` override (MixinMinecraft_RedirectedPacket.java:23-59 — the dimension-context-preserving re-queue) | GONE (as a mechanism) | Vanilla packet thread-hop no longer goes through the Minecraft event loop. New path: `PacketUtils.ensureRunningOnSameThread(packet, listener, PacketProcessor)` (`PacketUtils.java:21-26`) → `PacketProcessor.scheduleIfPossible(listener, packet)` queues a `ListenerAndPacket` record (`net/minecraft/network/PacketProcessor.java:26-32`); drained by `processQueuedPackets()` :34-40 (`ListenerAndPacket.handle()` :48-62 calls `packet.handle(listener)` :51). Client instance: `Minecraft.packetProcessor` field :369, built `new PacketProcessor(this.gameThread)` :730, drained in the run loop `Minecraft.java:1170`, getter `packetProcessor()` :2922. | **Top-risk item.** The re-queued packet never becomes a `Runnable` through `wrapRunnable`, so IP's ThreadLocal-wrap trick has no effect on packets. The equivalent interception must wrap `PacketProcessor.scheduleIfPossible` (capture the redirection ThreadLocal into the queued entry) and/or `ListenerAndPacket.handle`/`processQueuedPackets` (restore context around `packet.handle`). The "no-delay" `scheduleExecutables` override also loses its packet purpose: packets drained by `processQueuedPackets` run outside event-loop task bookkeeping (`runningTask()` is not set), so nested `minecraft.execute(...)` on the game thread already runs inline (`BlockableEventLoop.java:49-50` returns `!isSameThread()`; `ReentrantBlockableEventLoop.java:11-16` unchanged). Re-derive which guarantees are still needed at the design stage. |
| `Entity.lerpTo(x, y, z, yaw, pitch, steps)` 6-arg (McHelper.adjustVehicle :321-324 — interpolation kill) | GONE | Entity interpolation is now an `InterpolationHandler` object: `Entity.getInterpolation()` returns `@Nullable InterpolationHandler` (`Entity.java:2550`); `InterpolationHandler.interpolateTo(Vec3, float yRot, float xRot)` (`InterpolationHandler.java:49`), `cancel()` :111, active check `Entity.java:2522` (`hasActiveInterpolation`). | `adjustVehicle`'s lerp-kill becomes `vehicle.getInterpolation()` null-check + `cancel()` (or `interpolateTo` at the target pos). Entities without a handler (returns null) never interpolate — no call needed. |
| `Entity.createCommandSourceStack()` (McHelper.invokeCommandAs :436-443) | GONE (for generic entities) | Only `ServerPlayer.createCommandSourceStack()` (`ServerPlayer.java:1768`) and `MinecraftServer.createCommandSourceStack()` (`MinecraftServer.java:1693`) remain; `Entity` only has `createCommandSourceStackForNameResolution(ServerLevel)` (`Entity.java:3616`). Vanilla pattern for an arbitrary source: construct directly — `new CommandSourceStack(CommandSource.NULL, pos, Vec2.ZERO, level, LevelBasedPermissionSet.GAMEMASTER, textName, displayName, level.getServer(), entity)` (`SignBlockEntity.java:216-222`; public ctor `CommandSourceStack.java:69`). | `invokeCommandAs(Entity, ...)` must build the stack manually (entity pos/rot/level/name + the entity as source) — mirror the SignBlockEntity pattern. Permission arg: see CHANGED C21. |
| `ServerPlayer.displayClientMessage(Component, boolean)` (McHelper.serverLog :123, sendMessageToFirstLoggedPlayer :811) | GONE | Zero matches repo-wide. Replaced by `sendSystemMessage(Component)` (`ServerPlayer.java:1783`) / `sendOverlayMessage(Component)` :1788 (the old actionBar=true) / 2-arg `sendSystemMessage(Component, boolean overlay)` :1800. | Both IP call sites pass actionBar=false → `sendSystemMessage(Component)`. |
| `GlUtil.getVendor()` (IPMcHelper.isNvidiaVideocard :317-319) | GONE | `GlUtil` (`com/mojang/blaze3d/opengl/GlUtil.java:8`) now contains only `selectBufferBindTarget`. Vendor info is backend-agnostic: `RenderSystem.getDevice().getDeviceInfo().vendorName()` — `DeviceInfo` record `com/mojang/blaze3d/systems/DeviceInfo.java:8-20` (`vendorName` :10, also `backendName` :13, `type` :19). GL backend fills it from `GlStateManager._getString(GL_VENDOR)` (`GlHeuristics.java:68`); Vulkan from `VulkanPhysicalDevice.vendorName()` (`VulkanPhysicalDevice.java:138-151`). | Straight swap. Note 26.2 has **two GPU backends** (`com/mojang/blaze3d/opengl/GlBackend.java`, `com/mojang/blaze3d/vulkan/VulkanBackend.java`) — vendor strings differ per backend. |
| Raw depth clamp: `GL11.glDisable/glEnable(GL32.GL_DEPTH_CLAMP)` (CHelper.disableDepthClamp/enableDepthClamp :138-146, gated by `IPGlobal.enableClippingMechanism`) | GONE — **UNKNOWN-NEEDS-DESIGN** | Grep for `depthClamp|DEPTH_CLAMP|DepthClamp` over all of mc262-ref: **zero matches**. Neither `RenderPipeline.Builder` (full method list, MIGRATION_API_MAP "RenderPipeline.Builder" section) nor `DepthStencilState` (`com/mojang/blaze3d/pipeline/DepthStencilState.java:8` — `(CompareOp depthTest, boolean writeDepth, float depthBiasScaleFactor, float depthBiasConstant)`) has a depth-clamp toggle. | No vanilla 26.2 mechanism exists. Raw LWJGL GL calls remain *possible only on the GL backend* (this repo's stencil-direct work verified raw-GL state persists — see memory `stencil-direct-rework-status`), and are meaningless under the Vulkan backend. How IP's clipping fidelity is preserved is a design-stage decision for the render slice; do not silently drop. |
| `GL11.glGetError()` debug check (CHelper.checkGlError/doCheckGlError :69-86, gated by `IPGlobal.doCheckGlError`) | GONE | No vanilla polling-glGetError analog; GL-backend debug plumbing is `com/mojang/blaze3d/opengl/GlDebug.java` (driver debug-message callbacks). Raw `GL11.glGetError()` is GL-backend-only. | Debug-only utility. Keep behind a GL-backend check (`DeviceInfo.backendName`, `DeviceInfo.java:13`) or retire at design stage. |

---

## 2. CHANGED (exists, but signature/location/semantics moved)

| IP usage (where) | Verdict | 26.2 replacement + citation | Migration note |
|---|---|---|---|
| **`ClientLevel` ctor** `(ClientPacketListener, ClientLevelData, ResourceKey<Level>, Holder<DimensionType>, int chunkLoadDistance, int simDistance, Supplier<ProfilerFiller>, LevelRenderer, boolean isDebug, long biomeZoomSeed)` (ClientWorldLoader.java:445-456) | CHANGED | `ClientLevel(ClientPacketListener, ClientLevelData, ResourceKey<Level>, Holder<DimensionType>, int serverChunkRadius, int serverSimulationDistance, LevelExtractor, boolean isDebug, long biomeZoomSeed, int seaLevel)` — `ClientLevel.java:238-249`. Profiler supplier param REMOVED; `LevelRenderer` param → `LevelExtractor`; NEW trailing `int seaLevel`. Vanilla construction: `ClientPacketListener.java:507-518` (login) and :1262 (respawn), passing `this.minecraft.levelExtractor` (:514) and `spawnInfo.seaLevel()` (:504). | **seaLevel gap:** vanilla obtains it only from `CommonPlayerSpawnInfo.seaLevel()` (`ClientPacketListener.java:504`) — there is no client-local source for a not-yet-visited dimension (dimension type does not carry it). IP's `DimIdSyncPacket` carries only dim→dim-type ids (q_misc_util/MiscNetworking.java:104-125), so the dim-sync channel must be extended to carry per-dim sea level, or an equivalent server→client source designed. **UNKNOWN-NEEDS-DESIGN** (protocol addition, outside this slice's vanilla mapping). The `LevelExtractor` arg interacts with the per-dim renderer design (see §4). |
| **`LevelRenderer` ctor** `(Minecraft, EntityRenderDispatcher, BlockEntityRenderDispatcher, RenderBuffers)` (ClientWorldLoader.java:404-409) | CHANGED | 9-arg: `LevelRenderer(EntityRenderDispatcher, BlockEntityRenderDispatcher, ModelManager, TextureManager, AtlasManager, ShaderManager, GameRenderer, int width, int height)` — `LevelRenderer.java:131` (per MIGRATION_API_MAP). Vanilla template: `Minecraft.java:638-648` (`this.entityRenderDispatcher, this.blockEntityRenderDispatcher, this.modelManager, this.textureManager, this.atlasManager, this.shaderManager, this.gameRenderer, window w/h`); it self-serves `RenderBuffers` and the shared `LevelRenderState` from GameRenderer (`LevelRenderer.java:145`, :151). | Secondary-renderer creation must follow `Minecraft.java:638-651` — LevelRenderer ctor (:638-648), the paired `LevelExtractor` (`new LevelExtractor(minecraft, gameRenderer.gameRenderState().levelRenderState, levelRenderer)` :649), and **TWO reload-listener registrations**: the extractor (:650) AND `this.levelRenderer.cloudRenderer()` (:651). A secondary renderer built without the :651 registration silently misses cloud-resource reloads. ⚠ All secondaries constructed this way share ONE `LevelRenderState` (final on `GameRenderState`, `GameRenderState.java:10`) — see §4. |
| `LevelRenderer.setLevel(@Nullable ClientLevel)` (create :464, cleanup :226) | CHANGED (moved) | `LevelExtractor.setLevel(@Nullable ClientLevel)` — `LevelExtractor.java:393` (per MIGRATION_API_MAP; re-confirmed). Also sets `shouldResetLevelRenderData = true` (:402) which triggers `levelRenderer.resetLevelRenderData()` on next extract (:105-107). | The null-out on cleanup goes to the extractor. GameRenderer also has its own `setLevel` (`:705`, per MIGRATION_API_MAP — lighting/camera only; calls `mainCamera.setLevel` `GameRenderer.java:710`). |
| `LevelRenderer.onResourceManagerReload(ResourceManager)` (post-create GPU alloc :466) | CHANGED (moved) | `LevelExtractor.onResourceManagerReload(ResourceManager)` — `LevelExtractor.java:389`; `LevelExtractor implements ResourceManagerReloadListener` (`LevelExtractor.java:71`); vanilla registers it: `Minecraft.java:650` `this.resourceManager.registerReloadListener(this.levelExtractor);` | Per MIGRATION_API_MAP; LevelRenderer no longer implements the listener itself, but its `cloudRenderer()` is registered as a SECOND reload listener (`Minecraft.java:651`) — see the ctor row above. |
| `LevelRenderer.allChanged()` (reload cascade :532 + mixin TAIL target MixinLevelRenderer.java:403) | CHANGED (moved) | `LevelExtractor.allChanged()` — `LevelExtractor.java:406`; vanilla calls it on e.g. resource-pack reload `Minecraft.java:1047`. | The `_onWorldRendererReloaded` cascade mixin retargets `LevelExtractor.allChanged` TAIL. |
| `LevelRenderer.close()` (secondary-renderer disposal, ClientWorldLoader.java:228 in `disposeWorldRenderer`) | CHANGED (internals) | Survives: `LevelRenderer implements AutoCloseable` — `LevelRenderer.java:95`; `public void close()` :773. Body changed vs the 1.21.3 GPU-teardown shape: it now calls `resetLevelRenderData()` :774 (method at :878 — releases the ViewArea's buffers and disposes the SectionRenderDispatcher), then closes the sub-renderers (`entityOutlineTarget.destroyBuffers()` :775, skyRenderer :776-778, chunkLayerSampler :780-782, worldBorderRenderer :784, cloudRenderer :785, weatherEffectRenderer :786). | `disposeWorldRenderer`'s `worldRenderer.close()` call survives as-is; semantics are now "reset render data + close sub-renderers," not the old buffer disposal. Re-diff the `portal_fullyDispose` duck's extra teardown against the new close() body when porting. |
| `Minecraft.updateLevelInEngines(ClientLevel)` (cleanup HEAD hook, MixinMinecraft.java:156-175) | CHANGED | Now **private**, 1-arg delegates to a new 2-arg overload: `updateLevelInEngines(@Nullable ClientLevel)` `Minecraft.java:2191-2193` → `updateLevelInEngines(@Nullable ClientLevel, boolean stopSound)` :2195-2206. Body: soundManager.stop (cond.), `setCameraEntity(null)`, `pendingConnection = null`, `levelExtractor.setLevel(level)` :2202, `particleEngine.setLevel(level)` :2203, `gameRenderer.setLevel(level)` :2204. NEW public `Minecraft.setLevel(ClientLevel)` :2059-2062 (`this.level = level; updateLevelInEngines(level)`) — called from `handleLogin` (`ClientPacketListener.java:519`). Callers of the null path: `disconnect(...)` :2146, `clearClientLevel` :2177. | Hook the **2-arg** overload HEAD to catch all paths (the 1-arg delegates into it). The cleanup semantics ("world exit when level==null") still hold: null is passed at :2146/:2177. |
| `Minecraft.particleEngine` level reassignment via duck `IEParticleManager.ip_setWorld` (ClientWorldLoader.java:561) | CHANGED (duck now unnecessary) | `ParticleEngine.setLevel(@Nullable ClientLevel)` is **public vanilla API** — `ParticleEngine.java:131`; level field `protected ClientLevel level` :31. `particleEngine` field still `public final` `Minecraft.java:285`. | Replace the duck call with the vanilla setter in `withSwitchedWorld`. |
| `Minecraft.levelRenderer` `@Mutable`-reassign (MixinMinecraft.java:48-51; ClientWorldLoader.java:562) | CHANGED (swap set grows) | Field still exists: `public final LevelRenderer levelRenderer` `Minecraft.java:281` (final → still needs `@Mutable`). But 26.2 adds a **sibling that must be swapped in lockstep**: `public final LevelExtractor levelExtractor` `Minecraft.java:280` — vanilla routes per-frame extraction (`GameRenderer.java:389` `minecraft.levelExtractor.extract(...)`), block/section dirtying (`ClientLevel.java:791-804` forwards to `this.levelExtractor`, the ClientLevel's own final back-reference `ClientLevel.java:152`), debug renderer (`ClientPacketListener.java:530`), metrics (`Minecraft.java:1512`) through it. | See §4 — the `withSwitchedWorld` swap set and the per-dim render objects both change shape. This repo's promote/demote extractor work already hit the `ClientLevel.levelExtractor` identity trap (memory: `nether-block-freeze-orphaned-extractor`). |
| `PacketUtils.ensureRunningOnSameThread(Packet, T, BlockableEventLoop)` (behavioral dependency of redirection, PacketRedirectionClient.java:80-87) | CHANGED | `ensureRunningOnSameThread(Packet<T>, T, PacketProcessor)` — `PacketUtils.java:21-26` (server-level convenience overload :17-19). Throws the same `RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD` :24 after `packetProcessor.scheduleIfPossible(listener, packet)` :23. | The exception-based flow control survives; the *queue* changed (see GONE row on the requeue interception). |
| `GameRenderer.getMainCamera()` (ClientWorldLoader.java:188; CHelper.getCurrentCameraPos :118) | CHANGED (renamed) | `mainCamera()` — `GameRenderer.java:657` (field `private final Camera mainCamera = new Camera()` :125). | Rename only. |
| `Camera.getPosition()` (ClientWorldLoader.java:189; CHelper.java:119) | CHANGED (renamed) | `Camera.position()` — `Camera.java:359`. Mutation targets for the IECamera duck: `setPosition(double,double,double)` :349 / `setPosition(Vec3)` :353 (both `protected`), `reset()` :484. NEW: `Camera.setLevel(@Nullable ClientLevel)` :491 — the camera now holds a level reference (set by `GameRenderer.setLevel`, `GameRenderer.java:710`). | `portal_setPos` duck unchanged in approach. The new camera-level reference is a §4 swap-set candidate. |
| `McHelper.newResourceLocation` → `ResourceLocation.fromNamespaceAndPath/parse` (McHelper.java:89-94) | CHANGED (class renamed) | `Identifier.fromNamespaceAndPath(String, String)` — `Identifier.java:40`; `Identifier.parse(String)` :44. | Global rename (header note). |
| `RegistryAccess.registryOrThrow(...)` (ClientWorldLoader.java:434-436, :616) | CHANGED (renamed) | `RegistryAccess.lookupOrThrow(ResourceKey<? extends Registry<? extends E>>)` — `RegistryAccess.java:21`. Vanilla usage: `ClientPacketListener.java:1809`. | Rename only. |
| `Registry.getHolderOrThrow(ResourceKey)` (dim-type holder resolve :434-436) / `Registry.get(...)` (biome check :622-627) | CHANGED (renamed) | `getOrThrow(ResourceKey<T>)` → `Holder.Reference<T>` via `HolderGetter` (`HolderGetter.java:11`; `Registry` extends `HolderLookup.RegistryLookup`, `Registry.java:25`). Nullable direct value: `getValue(ResourceKey)/getValue(Identifier)` `Registry.java:66-68`; Optional holder: `get(int)/get(Identifier)` :132-134. | `getId(T)` :64 and `keySet()` :91 unchanged (see SAME). |
| `LevelAccessor.getMinBuildHeight/getMaxBuildHeight/getMinSection/getMaxSection` (McHelper.getMinY/getMaxYExclusive/getMinSectionY/getMaxSectionYExclusive :864-886) | CHANGED (renamed + **inclusivity flip**) | `LevelHeightAccessor` (`LevelHeightAccessor.java`): `getMinY()` :9; `getMaxY()` :11-13 = `minY + height - 1` (**INCLUSIVE** — old getMaxBuildHeight was exclusive); `getMinSectionY()` :19; `getMaxSectionY()` :23-25 = `blockToSectionCoord(getMaxY())` (**INCLUSIVE** — old getMaxSection was exclusive). | ⚠ Off-by-one hazard: `getMaxYExclusive` → `getMaxY() + 1`; `getMaxSectionYExclusive` → `getMaxSectionY() + 1`. `getMaxContentYExclusive` (`logicalHeight() + minY`) unaffected (`DimensionType.logicalHeight` intact, `DimensionType.java:37`). |
| `ChunkPos.asLong(x,z)` / `.toLong()` (McHelper.java:338 etc.) | CHANGED (renamed) | `ChunkPos.pack(int,int)` — `ChunkPos.java:73`; instance `pack()` :69; `pack(BlockPos)` :81. `getRegionX/getRegionZ` unchanged :128-140. | Rename only. |
| `ChunkMap.TrackedEntity.broadcastAndSend(Packet)` (McHelper.sendToTrackers :450-458) | CHANGED (renamed) | `TrackedEntity.sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener>)` — `ChunkMap.java:1352-1357`; tracker-only variant `sendToTrackingPlayers` :1345; both are `ServerEntity.Synchronizer` interface methods (`ChunkMap.java:1320`). `TrackedEntity` is now `private class` :1320. | Duck/accessor approach unchanged; note the tightened packet generic. |
| `Entity.saveWithoutId(CompoundTag)` / `.load(CompoundTag)` (McHelper.copyEntity :399-404) | CHANGED | `saveWithoutId(ValueOutput)` — `Entity.java:2059`; `load(ValueInput)` :2133. NBT bridging via `TagValueOutput`/`TagValueInput` (vanilla read example `ClientPacketListener.java:1475` `TagValueInput.create(reporter, this.registryAccess, tag)`). | The copyEntity round-trip must construct Tag-backed ValueOutput/ValueInput (with a problem reporter + registry access). |
| `EntityType.create(Level)` (copyEntity :399) | CHANGED | `create(Level, EntitySpawnReason)` — `EntityType.java:298` (also `create(Level, EntitySpawnRequest)` :302). | Pick the reason vanilla uses for load-style creation (`EntitySpawnReason.LOAD`) — confirm against the entity-sync slice's mapping when it lands. |
| `Entity.sendSystemMessage(Component)` (ScaleUtils server reject :156) | CHANGED (receiver narrowed) | Only on players now: `Player.sendSystemMessage(Component)` — `Player.java:1342`; `ServerPlayer` override :1783. Gone from `Entity` (grep: no match in Entity.java). | ScaleUtils' message must guard `instanceof Player` (the illegal-scale reject path targets whatever crossed; non-players just get silently clamped — preserve IP's observable behavior for players). |
| `CommandSourceStack.withPermission(int)` (invokeCommandAs :436) | CHANGED | `withPermission(PermissionSet)` — `CommandSourceStack.java:261`. The old level-2 equivalent used by vanilla self-executed commands: `LevelBasedPermissionSet.GAMEMASTER` (`ServerFunctionManager.java:91`, `AdvancementRewards.java:87`, `SignBlockEntity.java:220`). `withSuppressedOutput()` unchanged :240. | `withPermission(2)` → `withPermission(LevelBasedPermissionSet.GAMEMASTER)`. |
| `new ClickEvent(Action.OPEN_URL, url)` / `(Action.RUN_COMMAND, cmd)` (McHelper.getLinkText :423-429; IPMcHelper.getTextWithCommand :291) | CHANGED | `ClickEvent` is now an interface (`ClickEvent.java:19`) with records: `ClickEvent.OpenUrl(URI)` :126, `ClickEvent.RunCommand(String)` :137 (actions enum retained :25-27). `Style.withClickEvent(@Nullable ClickEvent)` unchanged (`Style.java:303`). | `OpenUrl` takes a `java.net.URI`, not a String — parse/validate at call site. |
| `Minecraft.gui.getChat().addMessage(Component)` (CHelper.printChat :88-94) | CHANGED | Chat moved under the HUD: `minecraft.gui.hud.getChat()` (`Gui.hud` public final `Gui.java:72`; `Hud.getChat()` `Hud.java:1264`). `addMessage(Component)` is now private (`ChatComponent.java:255`); public entry points: `addClientSystemMessage(Component)` :243 / `addServerSystemMessage(Component)` :247. | `printChat` → `gui.hud.getChat().addClientSystemMessage(...)` (client-originated text). |
| `GameProfile.getId()` (CHelper.getClientPlayerListEntry :46-47) | CHANGED (authlib record-style) | `profile.id()` — vanilla usage `PlayerInfo.java:36`, `PlayerTabOverlay.java:208`; `name()` (`AbstractClientPlayer.java:29`). `Player.getGameProfile()` still exists (`AbstractClientPlayer.java:29`). Vanilla's own "my PlayerInfo" idiom: `Minecraft.getInstance().getConnection().getPlayerInfo(this.getUUID())` (`AbstractClientPlayer.java:38-41`). | Rename only. |
| `Util.backgroundExecutor()` (McHelper multi-threaded finding :168) | CHANGED (package + return type) | `net.minecraft.util.Util.backgroundExecutor()` returns **`TracingExecutor`** — `util/Util.java:252`. | Import move; `TracingExecutor` is an `Executor` — `CompletableFuture.supplyAsync(..., executor)` usage pattern survives. |
| `EntityType.Builder` / entity-type registration values (IPModMain.registerEntityTypes :162-213) | CHANGED (+ FABRIC-API routing) | `EntityType.Builder.of(EntityFactory, MobCategory)` — `EntityType.java:479`; `build(ResourceKey<EntityType<?>>)` :590 (takes a ResourceKey, not a string id). | Registration itself is loader-routed (see §3). The `ENTITY_TYPE` statics' builder chains must adopt the ResourceKey-based `build`. |

---

## 3. FABRIC-API / external-library touchpoints (flagged for the loader abstraction)

These are not vanilla-MC touchpoints; each must route through the mod's multiloader (common/fabric/neoforge)
abstraction. No `current-mod-core.md` inventory exists yet, so they are flagged here per instruction.

| IP usage (where) | Kind | Note |
|---|---|---|
| `EventFactory.createArrayBacked` — all `Event<...>` objects (Helper.java:1424-1444; used by ClientWorldLoader events :60-62, IPGlobal :26-37, IPCGlobal :44-50) | FABRIC-API | Event abstraction needed in common. |
| `CommandRegistrationCallback.EVENT` (IPModMain.java:103-108, PortalCommand + argument types) | FABRIC-API | Command + command-argument-type registration are loader-specific (argument type registration especially: Fabric `ArgumentTypeRegistry` vs NeoForge registry). |
| `ClientCommandRegistrationCallback.EVENT` (IPModMainClient.java:103-105) | FABRIC-API | Client command registration. |
| `ClientPlayNetworking.registerGlobalReceiver` (MiscNetworking.java:135-140, DimIdSyncPacket) + `ImmPtlNetworking.init/initClient`, `ImmPtlNetworkConfig`, `PacketRedirection.init` (IPModMain.java:67-69, IPModMainClient.java:125-126) | FABRIC-API | Payload registration is loader-specific. The redirection envelope itself additionally depends on the CHANGED `PacketProcessor` model (§2/§1). |
| `ModInitializer` / `ClientModInitializer` entry points (IPModEntry.java:15, IPModEntryClient.java:67) | FABRIC-API | Entry points per loader. |
| `@Environment(EnvType.CLIENT)` annotations (ClientWorldLoader.java:54, CHelper.java:38, IPCGlobal.java:13, ScaleUtils.java:30) | FABRIC-API | mc262-ref (NeoForge-patched) uses `@OnlyIn(Dist.CLIENT)` — the multiloader layer already handles dist annotations. |
| `FabricLoader.getInstance().isModLoaded("porting_lib")` (IPMixinPlugin.java:24-29) | FABRIC-API | Loader query; the porting-lib special case itself is Fabric-ecosystem-only. |
| AutoConfig/Cloth: `AutoConfig.register`, `ConfigHolder.registerSaveListener` (IPModMain.java:146-152; IPGlobal.configHolder :17) | External lib | Config framework choice is a mod-level decision; `InteractionResult.SUCCESS` return value it uses is SAME (`InteractionResult.java:11`). |
| DimLib `DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT` (ClientWorldLoader.java:29, :85) | External lib | Dynamic-dimension update feed; needs a port/replacement decision at the dimension-management slice. |
| Registration lists `IPModMain.registerBlocks/registerEntityTypes` (:155-213) consumed by platform layer | FABRIC-API boundary | The list shape is loader-neutral; the consumer is per-loader. Entity-type builder change noted in §2. |
| `Unpooled.wrappedBuffer` (IPMcHelper.bytesToBuf :322-331) | Netty (library) | Unchanged; `FriendlyByteBuf` itself is SAME (below). |
| Codecs: `Codec.encode/decode`, `DynamicOps`, `JsonOps.INSTANCE`, `DataResult.getOrThrow` (McHelper.java:747-798) | DFU (library) | `com.mojang.serialization` is a library outside the decompile (mc262-ref `com/mojang/` holds only blaze3d/math/realmsclient); DFU API stable across this jump. |
| Raw LWJGL `GL11`/`GL32` (CHelper) | LWJGL (library) | See GONE rows — API exists, but only meaningful on the GL backend. |

---

## 4. Architectural fact sheet: the world-switch and per-dim renderer surface (facts, not proposals)

IP's `withSwitchedWorld` (ClientWorldLoader.java:544-583) swaps exactly: `Minecraft.level`,
`Minecraft.levelRenderer`, particle-engine level, `ClientPacketListener.level`. The 26.2 facts that bear on
that swap set and on per-dimension render objects:

1. **`Minecraft.levelExtractor`** — `public final LevelExtractor` `Minecraft.java:280`; per-frame extraction
   entry `GameRenderer.java:389`; all block/section-dirty routing flows through it (`ClientLevel.java:791-804`).
2. **`ClientLevel.levelExtractor`** — `private final` back-reference on every ClientLevel
   (`ClientLevel.java:152`, ctor param :245). A secondary ClientLevel is permanently bound to the extractor
   passed at construction. (This repo already hit the orphaned-extractor identity trap — memory
   `nether-block-freeze-orphaned-extractor`.)
3. **One shared `LevelRenderState`** — `public final` on `GameRenderState` (`GameRenderState.java:10`);
   `LevelRenderer` captures it at construction (`LevelRenderer.java:151`), `LevelExtractor` receives it as a
   ctor arg (`LevelExtractor.java:89` per MIGRATION_API_MAP; vanilla passes
   `gameRenderer.gameRenderState().levelRenderState`, `Minecraft.java:649`). Extractor writes / renderer reads
   the same instance. Multiple live renderers therefore contend on it unless the design gives secondaries their
   own `LevelRenderState` (the `LevelExtractor` ctor accepts any instance — that much is vanilla-supported).
4. **`Camera.setLevel`** — the camera holds a level reference (`Camera.java:491`), set via
   `GameRenderer.setLevel` (`GameRenderer.java:710`).
5. **`ParticleEngine.setLevel`** is public (`ParticleEngine.java:131`) — duck no longer needed.
6. **`ClientPacketListener.level`** — still a plain private field (`ClientPacketListener.java:390`), duck
   reassignment unchanged; `getLevel()` :2616.
7. **Reload listeners — TWO per renderer stack** — the extractor is a `ResourceManagerReloadListener`
   (`LevelExtractor.java:71`; registered `Minecraft.java:650`), and the renderer's `cloudRenderer()` is
   registered separately (`Minecraft.java:651`). The `LevelRenderer` itself is not a listener; a secondary
   stack that registers only the extractor misses cloud-resource reloads.
8. **Cleanup path** — `updateLevelInEngines(level, stopSound)` (`Minecraft.java:2195-2206`) nulls the level in
   extractor/particles/gameRenderer; `disconnect` :2146 and `clearClientLevel` :2177 pass null; new public
   `setLevel(ClientLevel)` :2059 is the login-time entry (`ClientPacketListener.java:519`).
9. **Remote-tick surface unchanged**: `ClientLevel.tick(BooleanSupplier)` :299, `tickEntities()` :453,
   `pollLightUpdates()` :281, `animateTick` :560 all survive, and `Minecraft.tick()` still calls
   `this.level.tick(() -> true)` at `Minecraft.java:1819` (injection anchor intact).

Which of 1-4 join the swap set (vs. per-dim object sets) is the design stage's call; the constraint set above
is exhaustive for this slice.

---

## 5. SAME (class + member exist compatibly; 26.2 citation per row)

### 5.1 Minecraft / event loop

| IP usage | 26.2 citation |
|---|---|
| `Minecraft.level` public mutable field (reassigned, ClientWorldLoader.java:560) | `public @Nullable ClientLevel level;` — `Minecraft.java:335` |
| `Minecraft.player` (initializeIfNeeded :361) | `public @Nullable LocalPlayer player;` — `Minecraft.java:336` |
| `Minecraft.gui` (printChat path) | `public final Gui gui;` — `Minecraft.java:290` |
| `Minecraft.getInstance()` (McHelper.readTextResource :900) | `Minecraft.java:2517` |
| `Minecraft.getConnection()` (ClientWorldLoader.java:553) | `public @Nullable ClientPacketListener getConnection()` — `Minecraft.java:2349` |
| `Minecraft.isPaused()` (tickRemoteWorld :162) | `Minecraft.java:2600` |
| `Minecraft.getResourceManager()` (renderer reload :466; CHelper icon :164) | `Minecraft.java:2576` |
| `Minecraft.isSameThread()` / `.execute(Runnable)` (thread checks; deferred renderer init IPModMainClient.java:76) | `BlockableEventLoop.isSameThread()` `util/thread/BlockableEventLoop.java:43`; `execute(Runnable)` :98 (Minecraft extends `ReentrantBlockableEventLoop<Runnable>`, `Minecraft.java:261`) |
| `ReentrantBlockableEventLoop.scheduleExecutables()` / `runningTask()` (override targets) | `util/thread/ReentrantBlockableEventLoop.java:11-16` (methods intact; their packet role changed — §1) |
| `Minecraft.wrapRunnable(Runnable)` (inject HEAD) | `Minecraft.java:2668-2670` (still the event-loop task wrapper; **no longer sees packet re-queues** — §1) |
| `Minecraft.gameThread` field (duck shadow) | `private Thread gameThread;` — `Minecraft.java:348` |
| `Minecraft.getEntityRenderDispatcher()` / `.getBlockEntityRenderDispatcher()` (renderer ctor args) | `Minecraft.java:2677` / :2681 |
| `Minecraft.tick()` injection anchor (after `ClientLevel.tick(BooleanSupplier)`) | `Minecraft.tick()` `Minecraft.java:1758`; `this.level.tick(() -> true)` :1819; `this.level.tickEntities()` :1797; `this.particleEngine.tick()` :1840 |

### 5.2 ClientLevel / level data

| IP usage | 26.2 citation |
|---|---|
| `ClientLevel.ClientLevelData(Difficulty, boolean hardcore, boolean isFlat)` + private `isFlat` (accessor IEClientLevelData) | ctor `ClientLevel.java:1157-1161`; `private final boolean isFlat` :1151 (no public getter — :1211/:1215 read it internally; accessor mixin still required) |
| `ClientLevel.mapData` private field (shared-by-reference duck, ClientWorldLoader.java:415, :459) | `private final Map<MapId, MapItemSavedData> mapData` — `ClientLevel.java:160`. NEW protected helpers `getAllMapData()` :992 (immutable copy) / `addMapData(Map)` :996 — copy-based, so **reference-sharing still needs the accessor duck** |
| `ClientLevel.tick(BooleanSupplier)` (:160) | `ClientLevel.java:299` |
| `ClientLevel.tickEntities()` (:159) | `ClientLevel.java:453` |
| `ClientLevel.pollLightUpdates()` (:166) | `ClientLevel.java:281` |
| `ClientLevel.animateTick(int,int,int)` (:195-197) | `ClientLevel.java:560` |
| `ClientLevel.entitiesForRendering()` (CHelper.java:128) | `ClientLevel.java:449` |
| `ClientLevel.getEntityCount()` (:267) | `ClientLevel.java:525` |
| `ClientLevel.getServerSimulationDistance()` (:432) | `ClientLevel.java:1091` |
| `ClientLevel.tickRateManager()` shared via duck (:462) | `ClientLevel.java:748`; field `private final TickRateManager tickRateManager` :155 (constructed :253 — sharing still requires `@Mutable` duck); class `world/TickRateManager.java` |
| `ClientLevel.getChunkSource().getLoadedChunksCount()` (:263) | `getChunkSource()` `ClientLevel.java:771`; `getLoadedChunksCount()` `ClientChunkCache.java:171` |
| `Level.isDebug()` (:454) | `Level.java:996-998` (also a ClientLevel ctor param, `ClientLevel.java:246`) |
| `Level.getBiomeManager()` + `biomeZoomSeed` (:455) | `Level.java:992`; `private final long biomeZoomSeed` `BiomeManager.java:16` (private — accessor/AW needed, as on 1.21.3) |
| `Level.dimension()` / `LevelAccessor.getGameTime()` | `Level.java:960`; `LevelAccessor.java:41` |
| `MapItemSavedData` / `MapId` (shared map-data value/key) | `world/level/saveddata/maps/MapItemSavedData.java` (file present); key type `MapId` (`ClientLevel.java:160`) |

### 5.3 Networking / packets

| IP usage | 26.2 citation |
|---|---|
| `ClientPacketListener.levels()` (:491) | `public Set<ResourceKey<Level>> levels()` — `ClientPacketListener.java:2624` |
| `ClientPacketListener.registryAccess()` (:431, :616) | `public RegistryAccess.Frozen registryAccess()` — `ClientPacketListener.java:2628` |
| `ClientPacketListener.getLevel()` (:553) | `public ClientLevel getLevel()` — `ClientPacketListener.java:2616` |
| `ClientPacketListener.level` private field (duck reassign :563) | `private ClientLevel level;` — `ClientPacketListener.java:390` |
| `ClientPacketListener.getPlayerInfo(UUID)` (CHelper.java:46) | `ClientPacketListener.java:2576` |
| `Packet.handle(listener)` raw dispatch (PacketRedirectionClient.java:62) | Vanilla itself dispatches this way — `PacketProcessor.java:51` `this.packet.handle(this.listener)` |
| `ClientboundBundlePacket` / `ClientboundCustomPayloadPacket` (redirect special cases) | `network/protocol/game/ClientboundBundlePacket.java`; `network/protocol/common/ClientboundCustomPayloadPacket.java` (files present) |
| `FriendlyByteBuf` (bytesToBuf/bufToBytes) | `network/FriendlyByteBuf.java` (file present) |

### 5.4 Rendering / camera

| IP usage | 26.2 citation |
|---|---|
| `GameRenderer.resetData()` (dynamic dim removal :271) | `GameRenderer.java:642-645` (also resets map textures + `mainCamera.reset()`) |
| `Camera` position mutation targets (IECamera duck: `portal_setPos`, restore) | `setPosition(double,double,double)` `Camera.java:349`, `setPosition(Vec3)` :353 (protected), `reset()` :484 |
| `PlayerInfo` (CHelper.java:45) | `client/multiplayer/PlayerInfo.java` (e.g. :36) |

### 5.5 Entity API

| IP usage | 26.2 citation |
|---|---|
| Direct writes `Entity.xo/yo/zo` (lastTickPosOf :109; setPosAndLastTickPos :252-258) | `public double xo/yo/zo;` — `Entity.java:216-218` |
| Direct writes `Entity.xOld/yOld/zOld` | `public double xOld;` — `Entity.java:242` (yOld/zOld adjacent) |
| `Entity.setPos(x,y,z)` (:320, :374) | `Entity.java:472` |
| `Entity.setPosRaw(x,y,z)` (:252) | `public final void setPosRaw(...)` — `Entity.java:3786` |
| `Entity.position()` | `Entity.java:3685` |
| `Entity.getBoundingBox()` (:888 etc.) | `Entity.java:3427` |
| `Entity.getDeltaMovement()` / `.setDeltaMovement(Vec3)` (:313, :330) | `Entity.java:3710` / :3714 |
| `Entity.getYRot()` / `.getXRot()` (adjustVehicle :322-323) | `Entity.java:3862` / :3879 |
| `Entity.getVehicle()` (:306) | `Entity.java:3596` |
| `Entity.getVehicleAttachmentPoint(Entity)` (:300) | `Entity.java:2389` |
| `Entity#positionRider` (vanilla-copy reference :295-298) | `Entity.java:2374` (public final) / :2380 (protected w/ MoveFunction) — re-diff the vanilla-copy against 26.2 body when porting |
| `Entity.level()` / `.getId()` (:452) | `Entity.java:3958` / :381 |
| `Entity.refreshDimensions()` (ScaleUtils :107, :122) | `Entity.java:3366` |
| `Level.addFreshEntity(Entity)` (spawnServerEntity :838) | `LevelWriter.addFreshEntity(Entity)` — `LevelWriter.java:28` |
| `Attributes.SCALE` (ScaleUtils :58-133) | `public static final Holder<Attribute> SCALE` — `ai/attributes/Attributes.java:89` |
| `LivingEntity.getAttributes().getInstance(...)` / `.getScale()` | `AttributeMap.getInstance(Holder<Attribute>)` `AttributeMap.java:45`; `LivingEntity.getScale()` `LivingEntity.java:558` |
| `AttributeInstance.getModifier/addOrReplacePermanentModifier/removeModifier/getBaseValue/setBaseValue` | `AttributeInstance.java` :65 (`getModifier(Identifier)`), :95, :117/:121 (`removeModifier(Identifier)`), :41, :45 |
| `AttributeModifier(id, amount, Operation.ADD_MULTIPLIED_TOTAL)` + `.amount()` | record `AttributeModifier(Identifier id, double amount, Operation operation)` — `AttributeModifier.java:14`; `ADD_MULTIPLIED_TOTAL` :41 |

### 5.6 Entity storage / lookup (duck targets intact)

| IP usage | 26.2 citation |
|---|---|
| `LevelEntityGetter<Entity>` via `IEWorld.portal_getEntityLookup()` (:630, :938) + `.get(UUID)` | `world/level/entity/LevelEntityGetter.java` (file); `LevelEntityGetterAdapter.get(UUID)` `LevelEntityGetterAdapter.java:24`; providers: `ClientLevel.getEntities()` `ClientLevel.java:1001-1003`, `ServerLevel.getEntities()` `ServerLevel.java:1705` |
| `EntitySectionStorage` private cache extracted by accessor (:550-551) | `private final EntitySectionStorage<T> sectionStorage;` — `LevelEntityGetterAdapter.java:11` (ctor :13) |
| `EntitySectionStorage#forEachAccessibleNonEmptySection` semantics (:586-590) + section traversal ducks | `EntitySectionStorage.forEachAccessibleNonEmptySection(AABB, AbortableIterationConsumer<EntitySection<T>>)` — `EntitySectionStorage.java:37`; `EntitySection.java` (file) |
| `EntityTypeTest.forClass(Class)` (:548) | `EntityTypeTest.java:6` |
| `AbortableIterationConsumer` / `EntityTickList` (duck types) | `util/AbortableIterationConsumer.java`; `world/level/entity/EntityTickList.java` (files present) |
| `ServerLevel.getAllEntities()` (:824) / `.areEntitiesLoaded(long)` (:491) | `ServerLevel.java:1636` / :1759 |

### 5.7 Server / chunk APIs

| IP usage | 26.2 citation |
|---|---|
| `ServerChunkCache.chunkMap` (:99-101) | `public final ChunkMap chunkMap;` — `ServerChunkCache.java:64` |
| `ChunkMap` private holder + tracker maps via duck `IEChunkMap` (:338, :447, :452) | `private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap` `ChunkMap.java:128` (protected lookup `getVisibleChunkIfPresent(long)` :255); `private final Int2ObjectMap<ChunkMap.TrackedEntity> entityMap` :146 |
| `ChunkMap#getPlayerViewDistance(ServerPlayer)` vanilla-copy (:237-245) | `private int getPlayerViewDistance(ServerPlayer)` — `ChunkMap.java:817` (re-diff the copy body) |
| `ChunkHolder.getTickingChunk()` (:342, :354) | `ChunkHolder.java:86-88` |
| `ChunkPos.getRegionX/getRegionZ` (:415-420) | `ChunkPos.java:128-140` |
| `MinecraftServer.getLevel(ResourceKey)` (:115, :853) | `MinecraftServer.java:1189` |
| `MinecraftServer.getPlayerList()` (:106, :233) | `MinecraftServer.java:1383` |
| `MinecraftServer.getRunningThread()` (:432) | `MinecraftServer.java:1488` |
| `MinecraftServer.getCommands()` (:442) | `MinecraftServer.java:1689` |
| `MinecraftServer.storageSource` field (:415) | `protected final LevelStorageSource.LevelStorageAccess storageSource;` — `MinecraftServer.java:217` |
| `LevelStorageAccess.getDimensionPath(ResourceKey<Level>)` (:417) | `LevelStorageSource.java:518` |
| `PlayerList.getViewDistance()` (:233) | `PlayerList.java:687` |
| `ServerPlayer.requestedViewDistance()` (:244) | `ServerPlayer.java:1878` (field :257) |
| `Commands.performPrefixedCommand(CommandSourceStack, String)` (:442) | `Commands.java:312` |
| `CommandSourceStack.withSuppressedOutput()` (:436) | `CommandSourceStack.java:240` |

### 5.8 World geometry / ray tracing

| IP usage | 26.2 citation |
|---|---|
| `Level.isClientSide()` (:501 etc.) | `Level.java:163` |
| `Level.clip(ClipContext)` (IPMcHelper.java:220) | `BlockGetter.clip(ClipContext)` — `BlockGetter.java:65` |
| `ClipContext` private `from`/`to` mutation via duck + `getFrom()/getTo()` (:200-201, :240-244) | `ClipContext.java:22-23` (private final from/to), `getTo()` :40, `getFrom()` :44, ctors :28/:32 |
| `BlockHitResult.miss(Vec3, Direction, BlockPos)` (:210) | `BlockHitResult.java:13` |
| `Direction.getUnitVec3()` (:211) | `Direction.java:379` |
| `BlockPos.containing(...)` (:213) | `BlockPos.java:90` (double,double,double), :94 (Position) |
| `BlockState.getCollisionShape(Level, BlockPos)` (getWallBox :471) | `BlockBehaviour.BlockStateBase.getCollisionShape(BlockGetter, BlockPos)` — `BlockBehaviour.java:665` (context overload :669) |
| `VoxelShape.isEmpty()` / `.bounds()` (:472-476) | `VoxelShape.java:73` / :39 |
| `AABB.minmax(AABB)` (:478) | `AABB.java:213` |
| `DimensionType.logicalHeight()` (:872) | record component `DimensionType.java:37` |

### 5.9 Registry / resources / misc

| IP usage | 26.2 citation |
|---|---|
| `Registries.DIMENSION_TYPE` / `Registries.BIOME` (:434, :616) | `core/registries/Registries.java:274` / :260 |
| `ResourceKey.create(registryKey, id)` (MiscNetworking) | `ResourceKey.java:26` (takes `Identifier`) |
| `Registry.getId(T)` / `.keySet()` (biome check :622-627) | `Registry.java:64` / :91 (`Set<Identifier>`) |
| `Holder<DimensionType>` / `ResourceKey<DimensionType>` | ClientLevel ctor param `ClientLevel.java:242`; `ResourceKey.java` |
| `ResourceManager.getResource(id)` → `Optional<Resource>` (:900-904; CHelper :164) | `ResourceProvider.getResource(Identifier)` — `ResourceProvider.java:15` (throwing variant :17); impls e.g. `ReloadableResourceManager.java:50` |
| `Util.getPlatform().openUri(URI)` (CHelper :106) | `util/Util.java:533` (`getPlatform`); `Util.OS.openUri(URI)` :1219 |
| `Mth.clamp` (:244) | `util/Mth.java:94` (int), :106 (double) |
| `SectionPos.of(...)` (:626, :724) | `core/SectionPos.java:41-61` (int³ :41, BlockPos :45, ChunkPos+y :49, EntityAccess :53, Position :57, long :61) |
| `Component.literal` / `.translatable` (:423; IPMcHelper :291-315) | `network/chat/Component.java:135` / :139 |
| `Style.withClickEvent` / `.withUnderlined` / `MutableComponent.withStyle` / `ChatFormatting` | `Style.java:303` / :237; `ChatFormatting.java` (root `net/minecraft/`) |
| `ConfirmLinkScreen` / `Screen` (CHelper :97-116) | `client/gui/screens/ConfirmLinkScreen.java` (file present) |
| `InteractionResult.SUCCESS` (config save listener, IPModMain.java:149) | `world/InteractionResult.java:11` |
| `ProfilerFiller.push/pop` (:398-400 via profiler) | `util/profiling/ProfilerFiller.java:13-17` (obtain via `Profiler.get()` — §1) |

---

## 6. Cross-slice pointers

- The renderer-internals of secondary-world rendering (ViewArea, SectionRenderDispatcher, submit pipeline) are
  covered by `MIGRATION_API_MAP.md` and belong to the render slice; this document only maps what
  `ClientWorldLoader` itself constructs/calls.
- `IPPerServerInfo`, `IPGlobal`/`IPCGloba`l knobs, `MyTaskList`, `LimitedLogger`, `CountDownInt`, `DQuaternion`,
  `IntBox` are IP-internal (no vanilla surface) — carried as-is.
- The `GravityChangerInterface` pass-throughs (McHelper :276, :912-922) are compat-layer, not vanilla — the
  default-gravity code paths reduce to the Entity members mapped in §5.5.
