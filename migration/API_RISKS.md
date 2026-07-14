# API RISKS — Ranked master list of 26.2 API gaps for the entity-portal migration

**Synthesized 2026-07-12 from all 16 slice maps in `migration/api-map/` + `MIGRATION_API_MAP.md`.**
Scope: every GONE / UNKNOWN-NEEDS-DESIGN item and every high-risk CHANGED item, deduplicated across
slices, ranked by how much of the port each blocks. Mechanical renames (`ResourceLocation`→`Identifier`,
`Util` package move, `moveTo`→`snapTo`, Optional NBT getters, `keySet()`, `ChunkPos` record accessors,
`position()`, `mainCamera()`, permission `PermissionSet` swaps, `.hud` hops) are NOT listed as risks —
they are absorbed by the slice maps and carry exact replacements there.

Citation conventions follow the slice docs: bare `File.java:line` or `26.2:` = decompiled vanilla at
`C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings); `IP:` = `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
(1.21.3); `MOD:` = `C:/Users/warwa/ModDev/Portals/Portal 26.2/common/src/main/java/com/warwa/seamlessportals`.
"Solved by mod" = the current 26.2 build already runs a working mechanism the port transplants into
(per the disposition audits `migration/api-map/current-mod-render.md` / `current-mod-core.md`).

---

## THE RANKED LIST

### R1. The extract→render split: re-entrant world rendering & the per-dimension client-world stack
**Blocks: the entire render slice, ClientWorldLoader, ClientTeleportationManager's client swap — the spine of the port.**

- **What IP needs:** mid-frame recursive re-invocation of `GameRenderer.renderLevel` after swapping
  `Minecraft.level`/`levelRenderer` (`IP:imm_ptl/core/render/MyGameRenderer.java:231-233`, swap set
  `IP:imm_ptl/core/ClientWorldLoader.java:544-583`); one `LevelRenderer` per dimension held in maps
  (`ClientWorldLoader.java:67-69`), reassigned via ducks (`IP:imm_ptl/core/ducks/IEMinecraftClient.java:8-17`).
- **What 26.2 offers:** the frame is split camera-update → extract → render
  (`26.2:net/minecraft/client/Minecraft.java:1290-1302`; `GameRenderer.java:372,379,396`);
  `GameRenderer.renderLevel` draws **exclusively from the pre-extracted `gameRenderState.levelRenderState`**
  (`GameRenderer.java:525,531-535`). A re-render therefore requires a per-dimension
  `LevelRenderState` + `LevelExtractor` (`26.2:net/minecraft/client/renderer/extract/LevelExtractor.java:89,95`)
  driven before `LevelRenderer.render(...)` (`LevelRenderer.java:156-165`). Compounding constraints:
  - `Minecraft.levelRenderer` is `public final` with a new lockstep sibling `public final LevelExtractor levelExtractor`
    (`Minecraft.java:280-281`) — swaps need `@Mutable` accessors, and every ClientLevel holds a
    **final back-reference** to the extractor passed at construction (`ClientLevel.java:152,245`).
  - All secondaries built the vanilla way share ONE `LevelRenderState` (final on `GameRenderState`,
    `state/GameRenderState.java:10`; captured by LevelRenderer at ctor `LevelRenderer.java:151`) —
    must be re-pointed per dimension (render-core G1; world-loader-root §4.3).
  - `ClientLevel` ctor changed: profiler supplier removed, `LevelRenderer`→`LevelExtractor` param,
    **new trailing `int seaLevel`** (`ClientLevel.java:238-249`) whose only vanilla source is
    `CommonPlayerSpawnInfo.seaLevel()` (`ClientPacketListener.java:504`) — **UNKNOWN-NEEDS-DESIGN:
    the dim-sync channel must be extended to carry per-dim sea level** for not-yet-visited dimensions
    (`world-loader-root.md` §2, ClientLevel-ctor row).
  - `LevelRenderer` ctor is 9-arg and self-wires renderBuffers/state from the passed GameRenderer
    (`LevelRenderer.java:131-154`); a secondary stack needs **two** reload-listener registrations
    (extractor `Minecraft.java:650` + `cloudRenderer()` `:651`) or it silently misses cloud reloads.
  - `LevelRenderer.tick()` is gone (destruction progress moved to `ClientLevel.destructionProgress`,
    `ClientLevel.java:178,435`) — IP's remote-renderer tick loop (`ClientWorldLoader.java:119-123`)
    has no direct target; role largely absorbed by ticking the remote ClientLevel + extraction
    (design-stage placement question).
- **Slices hit:** render-core (G1/C1/C11/C15), render-sub (hard item 3), world-loader-root (§1/§2/§4),
  teleportation-collision (GONE row 6 / top risk 1), mixin-client (facts 1-3, MixinMinecraft,
  MixinClientLevel), current-mod-core (§11.1 — "hardest gap").
- **Solved by mod?** **Substantially — the single biggest asset.** `MOD:render/PortalContextSwitch.java:520-598,1159-1173`
  already implements the per-dim renderer+extractor+state choreography with `@Mutable` accessors
  (`MOD:mixin/client/MinecraftAccessorMixin.java:27-49`, `MOD:mixin/client/LevelExtractorAccessor.java:32-39`),
  and the extractor-identity rules are battle-proven (memory: nether-block-freeze-orphaned-extractor).
  Disposition: `PortalContextSwitch` REPLACE-BY `MyGameRenderer` + context_management, **keeping its
  26.2 mechanics inside the ported shell** (current-mod-render §1.2). The genuinely unsolved residues:
  the seaLevel protocol addition and the remote-tick placement.

### R2. Frame-phase relocation of the pre-render pump (teleportation + animation before ALL world-state reads)
**Blocks: seamless crossing itself — the property the mod exists for.**

- **What IP needs:** `RenderStates.updatePreRenderInfo` → `StableClientTimer.update` →
  `ClientPortalAnimationManagement.update()` → `ClientTeleportationManager.manageTeleportation(false)`
  back-to-back at `GameRenderer.render` HEAD, **before** the frame positions its camera
  (`IP:imm_ptl/core/mixin/client/render/MixinGameRenderer.java:70-100`). On 1.21.3 `Camera.setup` ran
  later inside `renderLevel`, so a render-time teleport still rendered post-teleport.
- **What 26.2 offers:** the camera is positioned in `gameRenderer.update(deltaTracker)`
  (`26.2:Minecraft.java:1290`; `Camera.update` → `alignWithEntity`, `Camera.java:93-112,249`) and the
  level is extracted at `Minecraft.java:1295` — both **before** `GameRenderer.render`. A `render`-HEAD
  (or even `extract`-HEAD) inject teleports one frame late. Faithful anchor per `portal-animation.md` #14:
  **inside `Minecraft.renderFrame(Z)V` before the `gameRenderer.update(...)` call** (`Minecraft.java:1290`);
  the panorama/screenshot path (`Minecraft.java:2779-2781`) bypasses `renderFrame`, matching IP's 1.21.3
  non-firing there. Mixin-client cross-cut 3 calls the extract-vs-render phase decision "the single
  largest semantic change in the slice": every IP handler that mutated world/camera state mid-render must
  pick a phase (before extract = affects capture; inside render = GPU-only).
- **Slices hit:** portal-animation (#14 — its highest-risk item), mixin-client (§10 handler ①,
  cross-cut 3), teleportation-collision (top risk 8), current-mod-render (§2.2, A4).
- **Solved by mod?** **Mostly.** `GameRendererFrameCrossingMixin` already hooks `GameRenderer.update`
  HEAD as the 26.2 "before the camera is positioned" point, and current-mod-render §2.2 endorses it as
  the injection point for the ported `manageTeleportation`. One reconciliation for the spec:
  portal-animation.md prescribes the `renderFrame` call-site instead (so the panorama path does not
  fire) — both precede camera update; pick one and document the panorama behavior. Also settle A4's
  ordering question (the staged-upload flush needs a GPU-safe point outside the framegraph) in the spec,
  not ad hoc.

### R3. Cross-portal per-entity clip bracketing — no draw-layer hook exists
**Blocks: CrossPortalEntityRenderer — the hardest single 1:1-fidelity item. UNKNOWN-NEEDS-DESIGN.**

- **What IP needs:** toggling a GL clip plane around ONE entity's draw, forced out via
  `BufferSource.endBatch()/endLastBatch()` mid-batch splits
  (`IP:imm_ptl/core/render/CrossPortalEntityRenderer.java:110-143,208,308`), plus the private
  `LevelRenderer.renderEntity` duck (`IP:imm_ptl/core/ducks/IEWorldRenderer.java:19-27`).
- **What 26.2 offers:** no per-entity draw loop. Entities extract to `EntityRenderState`
  (`26.2:EntityRenderDispatcher.java:132`) and draw batched inside
  `FeatureRenderDispatcher.PreparedFrame.executeSolid()/executeTranslucent()` (`LevelRenderer.java:419,434`);
  there is **no mid-batch flush** to interleave GL state with entity draws (render-core G2/G23).
  Candidate mechanisms (unproven, need a design round): (a) per-draw clip uniform at
  `GlCommandEncoder.trySetup` RETURN keyed off submit-order metadata — the mod's clip hook already lives
  there (`MOD:mixin/client/GlCommandEncoderClipMixin.java:32-59`); (b) a one-entity `SubmitNodeStorage` +
  `FeatureRenderDispatcher.renderAllFeatures(storage)` (`26.2:feature/FeatureRenderDispatcher.java:112`)
  bracketed by raw-GL clip state. What DID survive: `EntityRenderDispatcher.shouldRender` is
  signature-identical and vanilla calls it at extraction (`EntityRenderDispatcher.java:127-129`;
  `LevelExtractor.java:254`) — IP's visibility-gate mixin ports 1:1 (render-core S35, corrected from
  the earlier G24 misclassification); `extractEntity` + `submit(state, cameraState, x,y,z, ...)` are
  public, killing the renderEntity duck (render-core G3).
- **Slices hit:** render-core (G2/G3/G23, top-risk 2), ducks-api-misc (G1), mixin-client
  (MixinMultiBufferSourceBufferSource TARGET-GONE).
- **Solved by mod?** **No.** The mod deleted its mirror-entity system and ships nothing equivalent;
  memory records "entities invisible THROUGH the portal window" as STILL OPEN. Largest unknown surface
  in the whole port — schedule its design round early, but note it can trail the core cutover
  (see verdict).

### R4. `ImmPtlViewArea` — vanilla ViewArea is now a thin wrapper over private storage with externalized dirty tracking
**Blocks: secondary-dimension terrain fidelity (unbounded grid, presets cache). UNKNOWN-NEEDS-DESIGN.**

- **What IP needs:** subclass surface on `ViewArea`: protected grid-size fields, `sections` array,
  `createSections`, `setDirty(int,int,int,boolean)` overrides (`IP:imm_ptl/core/render/ImmPtlViewArea.java:37-455`),
  installed by redirecting `new ViewArea` in `allChanged` (`IP:imm_ptl/core/mixin/client/render/MixinLevelRenderer.java:322-341`).
- **What 26.2 offers:** `ViewArea` wraps `private final RotatingSectionStorage<RenderSection>`
  (`26.2:ViewArea.java:15`; `net/minecraft/client/RotatingSectionStorage.java:15`) with no grid fields,
  no `createSections`, no `setDirty` — dirty tracking moved to `SectionUpdateTracker.SectionDirtyState`
  (`26.2:net/minecraft/client/SectionUpdateTracker.java:26,61-96`) driven through `LevelExtractor`
  (`LevelExtractor.java:423-473`); ViewArea construction moved to `LevelRenderer.invalidateCompiledGeometry`
  (`LevelRenderer.java:796-832`, `new ViewArea` at `:819-827`) — the redirect retargets there. Sections
  key on packed `sectionNode` longs via factory lambda (`ViewArea.java:35-37`;
  `SectionRenderDispatcher.java:216`). Compile API: `compileAsync/compileSync(RenderSectionRegion)`
  (`SectionRenderDispatcher.java:319,324`); the upload pump reworked to
  `lock()/uploadTerrainBuffersToGpu()/unlock()` (`:135-155`) — IP's `earlyRemoteUpload`
  (`IP:imm_ptl/core/render/MyRenderHelper.java:458-469`) becomes a per-secondary-dispatcher pump whose
  necessity **needs runtime verification** (render-core G26).
- **Slices hit:** render-core (G25, C22-C29, top-risk 3), mixin-client (MixinRenderSection, §11 ⑩),
  MIGRATION_API_MAP (ViewArea/RenderSection sections).
- **Solved by mod?** **Partially, with a standing fidelity tension.** The mod pins vanilla's bounded
  rotating array to the dest portal origin (memory: walking-limbo fix #5) instead of IP's unbounded
  presets-cached grid — proven but NOT IP's design, and it carries a documented latent bug (multi-portal
  same-dim dests >71 chunks apart collide in one bounded array). The port plan must decide: rebuild
  `ImmPtlViewArea` on 26.2 (subclass `ViewArea` — public ctor/overridable `repositionCamera`/
  `getRenderSectionAt` survive, `ViewArea.java:19,74,87` — backed by a mod-owned unbounded store, or
  replace the `new ViewArea` in `invalidateCompiledGeometry`), or keep the pinned-bounded deviation and
  document it. Mod-solved nearby: the extract()/compileSections pairing rule (memory: ow-holes) and the
  budgeted compile scheduling in `MOD:render/VisibleSectionDiscovery.java:170-279` (AMBIGUOUS A3 —
  compare once against IP's `ForceMainThreadRebuild`, `IP:imm_ptl/core/render/ForceMainThreadRebuild.java:12`).

### R5. Reversed-Z depth + stencil absent from the entire GPU abstraction (+ the Vulkan backend shadow)
**Blocks: RendererUsingStencil's depth/stencil choreography — but the substrate is the mod's proven ground.**

- **What IP needs:** raw-GL stencil choreography (`glStencilFunc/Op/Mask`, clear-view-area-depth-to-
  farthest, `glDepthFunc/glDepthRange`, depth clamp) and a stencil-enabled main framebuffer via
  porting-lib (`IP:imm_ptl/core/render/renderer/RendererUsingStencil.java:87-104,199-249`;
  `IP:imm_ptl/core/ducks/IEFrameBuffer.java:3-6`); GL occlusion queries for visibility prediction
  (`IP:imm_ptl/core/render/GlQueryObject.java:19-101`).
- **What 26.2 offers:** **no stencil at any layer.** `RenderPassDescriptor` carries color+depth only
  (`26.2:com/mojang/blaze3d/systems/RenderPassDescriptor.java:17-19`); `DepthStencilState` has no
  stencil ops despite the name (`DepthStencilState.java:8`); `RenderTarget.createBuffers` hardcodes
  `D32_FLOAT` (`RenderTarget.java:86`); stencil-capable formats exist unused (`GpuFormat.java:60-63`).
  Depth is **reversed-Z**: clear value 0.0 = far (`GameRenderer.java:404-408,439`), default compare
  `GREATER_THAN_OR_EQUAL` (`DepthStencilState.java:9`) — every IP depth constant/comparison flips
  (render-sub fact 2, hard items 1-2). GPU queries are timestamp-only (`GpuDevice.java:184`) —
  `QueryManager` stays raw GL. Depth clamp has NO vanilla toggle anywhere (grep zero;
  `world-loader-root.md` GONE). And **a Vulkan backend exists**
  (`26.2:com/mojang/blaze3d/vulkan/VulkanBackend.java`): every raw-GL mechanism (stencil, clip planes,
  occlusion queries, mirror cull-flip, depth clamp) silently does nothing under it. `GlStateManager`
  moved to `com.mojang.blaze3d.opengl`, its shadow caches must stay coherent (`GlStateManager.java:27-38`),
  and cull is the single pipeline funnel (`GlCommandEncoder.java:792-794`) — which is exactly why the
  cull-flip mixin still works.
- **Slices hit:** render-sub (G8/S5/C8/C14, hard items 1-2/5), render-core (facts 3-4, G36, C43, C45),
  platform-compat (RenderTarget/GlStateManager rows), world-loader-root (depth-clamp GONE), mixin-client
  (§0.7-0.8, MixinMainTarget/MixinRenderTarget TARGET-GONE).
- **Solved by mod?** **The substrate: yes, definitively.** The shipped stencil-direct chain
  (`MOD:mixin/client/stencil/GlBackendMixin` + `GlConstMixin` (D32_FLOAT→DEPTH24_STENCIL8) +
  `RenderTargetMixin` (GL_DEPTH_STENCIL_ATTACHMENT reattach) + `GlStateManagerMixin` (lastBoundFbo))
  is runtime-proven on 26.2, and raw-GL state is verified to persist across the framegraph (memory:
  stencil-direct-rework-status). NOT yet done: sign-flipping IP's `clearDepthOfThePortalViewArea`/
  `restoreDepthOfPortalViewArea` constants during the transplant — the geometry-sign bug class that
  has bitten twice — and documenting the Vulkan gap with a degrade path for `PortalRenderInfo`'s
  occlusion-query consumer.

### R6. The immediate-draw / MultiBufferSource model is gone — every IP mesh, blit and overlay re-expresses
**Blocks: ViewAreaRenderer, MyRenderHelper screen tris/FB composites, OverlayRendering, wand overlays, custom shaders.**

- **What IP needs:** `Tesselator`/`BufferUploader`/`RenderType.draw(MeshData)`/`DefaultVertexFormat.BLIT_SCREEN`,
  the `ShaderProgram`/`CompiledShaderProgram`/`CoreShaders` stack with loose uniforms, and global
  `RenderSystem.setShader/enableBlend/colorMask/depthMask` state
  (`IP:imm_ptl/core/render/ViewAreaRenderer.java:32-145`, `MyRenderHelper.java:65-435`,
  `OverlayRendering.java:72-161`).
- **What 26.2 offers:** submit→prepare→execute (MIGRATION_API_MAP headline). Single meshes: build via
  `BufferBuilder(ByteBufferBuilder, PrimitiveTopology, VertexFormat)` (`26.2:BufferBuilder.java:42`),
  upload to a `GpuBuffer`, draw via manual `RenderPass.setPipeline/setVertexBuffer/draw(count,1,0,0)`
  (`SkyRenderer.java:88-94,307-315`; `RenderPass.java:262`). Shaders: `RenderPipeline` + builder;
  uniforms declared on `BindGroupLayout` (`BindGroupLayout.java:82-96`), values via
  `RenderPass.setUniform`; GLSL loaded per `ShaderType` by `ShaderManager` (`ShaderManager.java:202,248`).
  Blend/masks are per-pipeline: IP's premultiplied `(ONE, ONE_MINUS_SRC_ALPHA, ZERO, ONE)` has **no
  vanilla constant** — needs the 4-factor `BlendFunction` ctor (`BlendFunction.java:33`; render-core
  G29/C42). Fullscreen blits are vertex-less 3-vertex pipelines (`RenderTarget.java:97-108`;
  `BLIT_SCREEN` format gone). Overlays: `BakedModel.getQuads`/`putBulkData` gone →
  `BlockStateModel.collectParts` + `submitBlockModel`/`submitBreakingBlockModel`
  (`LevelRenderer.java:679-703` is the `@IPVanillaCopy` template; render-core G33/G34). Wand/debug
  lines → the new Gizmos API (`net/minecraft/gizmos/Gizmos.java:31-103`, collector-scope constraint)
  or `submitCustomGeometry` (`OrderedSubmitNodeCollector.java:171,184`; platform-compat GONE rows,
  ducks G4).
- **Slices hit:** render-core (G5-G12, G29, G33/G34, G40, top-risks 4/7), render-sub, ducks-api-misc
  (G1/G2/G4/G11), platform-compat (wand + DebugRenderer rows), mixin-client (§0.4/0.9,
  MixinRenderSystem_Clipping / MixinCompiledShader / MixinShaderInstance TARGET-GONE).
- **Solved by mod?** **The mechanisms: yes.** `MOD:render/PortalRenderTypes` (KEEP — pipeline
  registration + `drawMesh`), `ShaderManagerCompilationCacheMixin` (GLSL source transform at
  `ShaderManager$CompilationCache.getShaderSource`), `GlCommandEncoderClipMixin` (per-draw clip uniform),
  `PortalRenderBuffersPool.endFramePooled()` (the mandatory `RenderBuffers.endFrame()` rule — GPU-leak
  lesson). The IP classes re-express onto these proven mechanisms; volume is large but every piece has a
  cited pattern. One fidelity trap re-flagged: do NOT "fix" `FrontClipping`'s JOML
  `Vector4f.mul(Matrix4fc)` to `mulTranspose` — the current idiom is empirically verified correct;
  the "row-vector" note in IP_DEVIATIONS_ANALYSIS.md is itself wrong (current-mod-render §1.6).

### R7. Client packet re-queue moved off the Minecraft event loop → `PacketProcessor` (redirection ordering hazard)
**Blocks: the packet-redirection receive path — the transport of ALL cross-dimension sync.**

- **What IP needs:** redirected packets re-submitted via `minecraft.execute(...)` landing in the SAME
  queue as vanilla's `ensureRunningOnSameThread` re-queues, plus the `wrapRunnable` ThreadLocal wrap and
  `scheduleExecutables` inline-execution override
  (`IP:q_misc_util/.../PacketRedirectionClient.java:70-87`; `MixinMinecraft_RedirectedPacket.java:23-59`).
- **What 26.2 offers:** `PacketUtils.ensureRunningOnSameThread(Packet, T, PacketProcessor)`
  (`26.2:network/protocol/PacketUtils.java:21-26`) schedules into `Minecraft.packetProcessor`
  (`Minecraft.java:369,730,2922`), drained in `runTick` **before** `runAllTasks()`
  (`Minecraft.java:1170-1172`). A verbatim port puts redirected packets in the later-drained executor
  queue while vanilla packets take the earlier queue → **per-frame cross-queue reordering that 1.21 did
  not have** — exactly the bug class already paid for (respawn-mislabel, walking-limbo). The
  order-faithful shape is fully specified in `network.md` §A: netty pass schedules the outer
  `ClientboundCustomPayloadPacket` via `packetProcessor().scheduleIfPossible(...)` + cancel; the
  game-thread re-invocation handles inline. `wrapRunnable`/`scheduleExecutables` keep a narrowed role.
  The redirection wire format itself survives 1:1 — `GameProtocols.CLIENTBOUND_TEMPLATE.bind` intact
  (`GameProtocols.java:131`): the single most version-sensitive line ports unchanged.
- **Slices hit:** network (G1, headline 2, §A), world-loader-root (requeue GONE row — its top risk),
  mixin-client (MixinClientPacketListener anchor descriptors), teleportation-collision
  (ensureRunningOnSameThread row).
- **Solved by mod?** **No (the mod's transport is a different architecture), but the hazard class is
  mapped** — "every packet-handler mixin needs an isSameThread guard" (memory:
  respawn-mislabel-phantom-blocks) is this same double-invocation shape. Implement §A exactly; this is
  where silent cross-dimension state corruption enters.

### R8. `ClientboundPlayerPositionPacket` is a record with a composite codec — the dimension stamp has nowhere to ride
**Blocks: position-sync correctness at crossings. UNKNOWN-NEEDS-DESIGN (both sides in lock-step).**

- **What IP needs:** appending a trailing dimension id onto the S2C position packet via write/ctor
  injects, read back into a duck field (IP `MixinPlayerPositionLookS2CPacket` + client counterpart).
- **What 26.2 offers:** `record ClientboundPlayerPositionPacket(int id, PositionMoveRotation change,
  Set<Relative> relatives)` with a composite `STREAM_CODEC`, **no FriendlyByteBuf ctor, no write
  method** (`26.2:ClientboundPlayerPositionPacket.java:12-21`) — a composite codec reads exactly its
  three fields; no tail slack. Candidates: wrap the static `STREAM_CODEC` (`@ModifyExpressionValue` on
  the `StreamCodec.composite` call in `<clinit>`) appending `writeResourceKey`/`readResourceKey`
  (`FriendlyByteBuf.java:586,591` survive), or move the stamp to a separate ImmPtl packet paired by
  teleport id (mixin-common §4; mixin-client §9). The C2S move-player packets are fine (hand-written
  `write`/`read` survive as the codec functions, `ServerboundMovePlayerPacket.java:101-224`). The
  related server `teleport` `@Overwrite` re-copies against `teleport(PositionMoveRotation, Set<Relative>)`
  (`ServerGamePacketListenerImpl.java:1261-1270`; `RelativeMovement` deleted → `Relative`).
- **Slices hit:** mixin-common (§4 position_sync), mixin-client (§9), teleportation-collision (top risk 5).
- **Solved by mod?** No — the mod stamps dimension context differently today (crossing payload carries
  it). Pick the codec-wrap idiom during the position-sync port and change server+client halves together.

### R9. Per-dimension fog / lightmap / environment-attribute state — vanilla is single-world-per-frame
**Blocks: correct dest-world visuals in the portal view (fog color/distance, lightmap, ambience).**

- **What IP needs:** per-dimension `LightTexture` swap (`IP:imm_ptl/core/render/context_management/DimensionRenderHelper.java:13-24`),
  `FogRendererContext` save/restore of vanilla's static fog fields + `getFogColorOf(destWorld)`
  (`IP:imm_ptl/core/render/context_management/FogRendererContext.java:84-105`), and the
  `StaticFieldsSwappingManager` pattern.
- **What 26.2 offers:** fog is an instance `FogRenderer` producing a `FogData` UBO with a **single
  WORLD ring-buffer slot per frame** (`26.2:fog/FogRenderer.java:55-83,167-202`) — writing dest-world
  fog into the same slot mid-frame corrupts later passes; a second rendered world needs its own
  buffer/instance (**UNKNOWN-NEEDS-DESIGN on ownership**, render-sub G2). The old static fog-smoothing
  state is now per-camera `EnvironmentAttributeProbe` (`Camera.java:80,87`) plus mutable shared
  `FogEnvironment` instances (`AtmosphericFogEnvironment.rainFogMultiplier`,
  `fog/environment/AtmosphericFogEnvironment.java:26`) — same-shaped cross-dimension leak, new home;
  **environment-state isolation UNKNOWN-NEEDS-DESIGN** (mixin-client MixinFogRenderer row).
  `StaticFieldsSwappingManager` has nothing left to swap for fog (compiles as-is; render-sub G3).
  Lightmap: `LightTexture` split into `Lightmap` (GPU, no-arg ctor) + `LightmapRenderStateExtractor`
  (hard-reads `minecraft.level`, `LightmapRenderStateExtractor.java:50-51`) + per-frame render
  (render-sub G4). Fog-off = `getBuffer(FogMode.NONE)` — vanilla's own empty-fog buffer
  (`FogRenderer.java:55-63,82`). Per-dim render differences generally became data-driven
  (`DimensionSpecialEffects` deleted → `DimensionType.Skybox` + `EnvironmentAttributes` +
  `CardinalLighting`; render-core G30-G32) — re-running extract for the dest dimension covers them.
- **Slices hit:** render-sub (G2/G3/G4, hard items 5/7), render-core (G20, G30-G32), mixin-client
  (MixinFogRenderer TARGET-GONE, MixinRenderSystem_Fog), world-loader-root (LightTexture GONE),
  teleportation-collision (lightmap-swap GONE row), ducks-api-misc (G3).
- **Solved by mod?** **Lightmap: essentially yes** — `MOD:render/DimensionRenderHelper.java:63-117` is
  already the Lightmap/LightmapRenderState shape the map prescribes (current-mod-render §1.8), delivered
  by `GameRendererLightmapMixin`; `lateUpdateLight` is ported (commit 3a2c14e). **Fog: partially** —
  PortalContextSwitch carries fog-buffer/UBO isolation mechanics, but per-dim `FogRenderer`/probe
  ownership and `FogEnvironment` isolation need the design pass.

### R10. Server chunk-loading ticket system reworked (TicketType registry record, TicketStorage, no comparators)
**Blocks: ImmPtlChunkTickets — the server half of portal chunk loading.**

- **What IP needs:** `TicketType.create("imm_ptl", comparator)` + `DistanceManager.addRegionTicket/
  removeRegionTicket/getTickets` + the `ChunkTaskPriorityQueueSorter` mailbox
  (`IP:imm_ptl/core/chunk_loading/ImmPtlChunkTickets.java:60-61,185,294-312`).
- **What 26.2 offers:** `TicketType` is `record TicketType(long timeout, @Flags int flags)` registered
  into `BuiltInRegistries.TICKET_TYPE` with private registration (`26.2:TicketType.java:10`;
  `BuiltInRegistries.java:337`); `Ticket` is non-generic (`Ticket.java:23-25`); ticket CRUD lives on
  `TicketStorage` (`addTicketWithRadius/removeTicketWithRadius/getTickets`,
  `TicketStorage.java:130-214`), reached via `DistanceManager.ticketStorage` (`DistanceManager.java:40`);
  the sorter/mailbox is deleted → `ThrottlingChunkTaskDispatcher` with public `submit/release`
  (`ChunkTaskDispatcher.java:49-62`). The empty-level keep-alive redirect retargets
  `ServerChunkCache.hasActiveTickets()` (`ServerLevel.java:408-418`; chunk-loading #39). Two explicit
  fidelity decision points: **flag bits** (FLAG_LOADING vs FLAG_SIMULATION — the piglin-flood lesson vs
  IP's simulation semantics; chunk-loading cross-cut 3, current-mod-core §11.5) and the
  `PlayerTicketTracker` takeover's **new uncovered path** — `DistanceManager.addPlayer` now adds a
  direct `PLAYER_SIMULATION` ticket (`DistanceManager.java:115,127`) that cancelling the four tracker
  methods does not touch (mixin-common §2 warning).
- **Slices hit:** chunk-loading (#17/#18/#23/#24/#39, cross-cuts 1-3), mixin-common (§2),
  current-mod-core (§11.5).
- **Solved by mod?** **Partially** — the mod runs a working 26.2 ticket path (`TicketTypeInvoker` KEEP;
  the radius-32 residency ticket is live) and owns the flag-semantics lesson (portal-view liveness gap:
  FLAG_LOADING-only vs FLAG_SIMULATION memory). Registry-phase registration (multiloader bootstrap, not
  first-use static init) and the two decisions above go in the port spec.

### R11. Entity persistence + SavedData both re-platformed (ValueInput/ValueOutput; SavedDataType codec; a silent-data-loss trap)
**Blocks: Portal entity NBT (sync + save), GlobalPortalStorage, every `EntityType` static field.**

- **What IP needs:** `readAdditionalSaveData/addAdditionalSaveData(CompoundTag)` overrides
  (`IP:imm_ptl/core/portal/Portal.java:233,366`), `Entity.saveWithoutId/load(CompoundTag)` round-trips
  (GlobalPortalStorage, McHelper.copyEntity), `SavedData.Factory` + `save()` override with **null**
  dataFixType (`IP:imm_ptl/core/portal/global_portals/GlobalPortalStorage.java:94-112,266`), and id-less
  `EntityType.Builder.build()` for the static `ENTITY_TYPE` self-instantiation pattern.
- **What 26.2 offers:** the whole entity save chain takes `ValueInput/ValueOutput`
  (`26.2:Entity.java:2040-2206`); the CompoundTag wire format survives via
  `TagValueOutput.createWithContext(...).buildResult()` / `TagValueInput.create(...)`
  (`TagValueOutput.java:27,152`; `TagValueInput.java:40`) — so `PortalSyncPacket` keeps carrying
  CompoundTag (portal-core C1 + hazard 3; the rotation double-write/float-read quirk reproduces exactly
  via `NumericTag` coercion, C4). `SavedData` is only a dirty flag; persistence is
  `SavedDataType(id, ctor, Codec, DataFixTypes)` (`SavedDataType.java:8`) — **`dataFixType` may not be
  null, and the failure mode is SILENT DATA LOSS**: the NPE is swallowed by `readSavedData`'s
  catch(Exception) and a fresh empty storage overwrites `global_portal.dat`
  (`SavedDataStorage.java:65-124`; portal-generation G1 — ~~UNKNOWN-NEEDS-DESIGN~~ **SETTLED by
  SPIKE-R11 (`migration/spikes/SPIKE-R11-saveddata.md`): pass `DataFixTypes.SAVED_DATA_COMMAND_STORAGE`
  (bit-exact round-trip proven incl. a real 3465→4903 datafixer pass; zero fixes target it). Nuances the
  spike added: (a) BOTH loaders patch the null-NPE in 26.2 (Fabric `handleNullDataFixType`, NeoForge
  binary patch) so null does not actually lose data on either loader — pass the real constant anyway
  (loader-independent); (b) the silent-loss funnel is REAL for corruption and codec rejects — two
  captured log signatures for S13's relog check are in the memo; (c) the per-dim data file LOCATION
  moved — see the portal-generation.md G1 erratum.** `EntityType.Builder.build(ResourceKey)` requires the id at build time
  (`EntityType.java:590`) — restructure `createPortalEntityType` (portal-core C10/hazard 4); Fabric
  `trackRangeBlocks(96)` ≙ vanilla `clientTrackingRange(6)` **chunks** (hazard 5 — writing 96 would mean
  a 1536-block radius; current-mod-core §11.6). Bounding-box caching inverts: `getBoundingBox()` is
  final; the override moves to `makeBoundingBox(Vec3)` + eager `setBoundingBox` pushes on every
  geometry-field change (portal-core G1/C2/hazard 1 — a missed push leaves a stale `bb` vanilla happily
  ticks on).
- **Slices hit:** portal-core (G1/G2/C1-C12, §5 hazards), portal-generation (G1, CHANGED 2-5, notes 2-3),
  world-loader-root (copyEntity row), network (C11-C13), portal-animation (#1/#20).
- **Solved by mod?** Isolated pieces (the mod already uses `EntitySpawnReason.LOAD` on 26.2). The bulk
  is new port work — mechanical once four decisions are recorded: DataFixTypes constant,
  EntitySpawnReason per call site (LOAD for deserialize, DIMENSION_TRAVEL for the recreate path,
  `Entity.java:3081`), entity-type id plumbing, tracking-range conversion. Also decide the
  defaulted-registry lookup semantics for `entity_type` (getValue → pig fallback matches 1.21.3;
  portal-generation note 3).

### R12. Collision & inside-block effects reworked around movement paths; every vanilla-copy re-derives
**Blocks: CollisionHelper (cross-portal collision) + the portal-clipped block-effects box + anticheat exemption.**

- **What IP needs:** redirect of `getBoundingBox()` inside `checkInsideBlocks` (substitute
  `ip_getActiveCollisionBox`, cancel on null); gravity-generalized copies of `Entity.collide`/
  `collideBoundingBox`/`collideWithShapes`; the anticheat `isPlayerCollidingWithAnythingNew` overwrite;
  `lerpTo(pos,rot,0)` interpolation kills
  (`IP:imm_ptl/core/mixin/common/collision/MixinEntity.java:154-179,315-340`;
  `IP:imm_ptl/core/CollisionHelper.java:250-378`).
- **What 26.2 offers:** `checkInsideBlocks` is movement-path based — per-axis segments via
  `Direction.axisStepOrder`, box derived per segment from `makeBoundingBox(to).deflate(1e-5)`
  (`26.2:Entity.java:1269-1310`) — **the bb-redirect has no anchor; the portal clip must apply to the
  per-segment box/path — needs design** (teleportation-collision top risk 2; mixin-common MixinEntity).
  The collide bodies changed internally (dynamic `axisStepOrder`, `collectCollidersIgnoringWorldBorder`,
  step-up epsilon; `Entity.java:1138-1252`) — **re-derive line-by-line from 26.2, never patch the
  1.21.3 copies.** The same re-derivation rule applies to every `@IPVanillaCopy` in the port:
  `setPosRaw` (new waypoint updates, `Entity.java:3800-3809`), `ServerGamePacketListenerImpl.teleport`,
  `scheduleExecutables`, `ChunkMap.onChunkReadyToSend` (must preserve the NEW
  `ServerChunkCache.onChunkReadyToSend` broadcast-queue call, `ChunkMap.java:690-701`),
  `doProcessUseItemOn` (`InteractionResult.shouldSwing` gone → `Success.swingSource()`, ducks G7),
  MyNbtTextFormatter (→ `SnbtPrinterTagVisitor`, ducks G8), `EntitySection`/`EntitySectionStorage`
  traversals, `Frustum.offsetToFullyIncludeCameraCube`, `ClientLevel.playSound`. Anticheat maps onto
  `isEntityCollidingWithAnythingNew` + `getPreMoveCollisions` (`ServerGamePacketListenerImpl.java:1243-1254`;
  `CollisionGetter.java:90-95`) — now also guarding vehicles (behavior-review flag). Interpolation kills
  re-target `InterpolationHandler` + `snapTo` + **`getPositionCodec().setBase`** gated by
  `isLocalInstanceAuthoritative` (`Entity.java:2550`; `ClientPacketListener.java:640-664`) — without
  setBase the movement dedup and vanilla delta packets drift (mod lesson: post-crossing-stutter).
  Riding bypasses: `ServerPlayer` no longer overrides `stopRiding` — the packets moved to
  `removeVehicle()` (`ServerPlayer.java:2137-2150`), so IP's bypass as written is INEFFECTIVE and must
  retarget (mixin-common MixinServerPlayer; teleportation-collision CHANGED row 4).
- **Slices hit:** teleportation-collision (GONE 1, CHANGED 5-13, top risks 2-4/7), mixin-common (§3/§5),
  world-loader-root (adjustVehicle), ducks-api-misc (directive 2), portal-core (C11 setRemoved note).
- **Solved by mod?** No (the mod's crossing architecture differs); the `setBase`/dedup interaction and
  the vehicle-crossing gap are known open items (memory: server-side-crossing-hunt task #11).

### R13. Secondary structural gaps (each fully mapped; lower blast radius)

- **R13a. Vanilla now teleports cross-dimension natively in paths IP patched.** Ender pearl: `onHit`
  runs its own `TeleportTransition` cross-dim teleport (`26.2:ThrownEnderpearl.java:103-147`) — IP's
  mixin must intercept/replace vanilla's branch (redirect the `teleport` calls when dims differ), not
  add a missing case (teleportation-collision C17). `ServerPlayer.changeDimension(DimensionTransition)`
  → `teleport(TeleportTransition)` (`ServerPlayer.java:1093`).
- **R13b. Cross-dimension UUID resolution is vanilla now.** `EntityReference` +
  `Level.getEntityInAnyDimension` (`Level.java:785`) obsolete IP's projectile-owner redirect
  (drop-candidate) and retype `lastHurtByMob`/`thrower` shadows (mixin-common §5 + cross-cut 2;
  portal-generation C19). Audit every IP cross-dim patch for obsolescence-by-vanilla before porting
  (also: `BiomeAmbientSoundsHandler` resolves the level live each tick — the IP mixin's purpose is
  vanilla-solved, mixin-client §5).
- **R13c. All synthetic-lambda anchors are dead.** Every `method_62xxx`/`method_41930` target must be
  re-derived from the **compiled 26.2 jar**, not the decompile (mixin-client cross-cut 1; render-core
  G37). The replacement pass lambdas are mapped (`addMainPass :391`, clear `:197`, weather `:474`,
  sky `:329`).
- **R13d. Height-bound inclusivity flips.** `getMaxY()`/`getMaxSectionY()` are now INCLUSIVE
  (`LevelHeightAccessor.java:11-25`); fix IP's `McHelper.getMaxYExclusive/getMaxSectionYExclusive`
  wrappers (+1), never the call sites — FastBlockAccess sizing and NetherPortalMatcher staging are
  silent-corruption surfaces (portal-generation CHANGED 8-9 + note 1; world-loader-root CHANGED).
- **R13e. GuiGraphics / Screen.render / DebugScreenOverlay gone.** GUI is extract-model:
  `Screen.extractRenderState(GuiGraphicsExtractor, ...)` (`Screen.java:116`;
  `GuiGraphicsExtractor.java:88-369`); F3 text is a registered `DebugScreenEntry` with **private**
  registration + per-profile enablement (`DebugScreenEntries.java:60-64`; `Minecraft.debugEntries`
  `Minecraft.java:292`) (mixin-client §1; ducks G5; q-misc G3/G4/G7). Affects wand screens, dim-stack
  GUI, RenderStates debug text.
- **R13f. `ClientChunkCache` rework + the loadedChunks delta-set protocol.** The delta feed
  (`ClientChunkCache.java:180-207` → `LevelExtractor.java:138-142` → SOG) did not exist in 1.21.3 and
  has no IP analog — chunk-loading calls it "the largest genuinely new work item". **Mod-solved:**
  `SeamlessClientChunkMap` (installed via `ClientLevelChunkSourceAccessor`), the SOG delta feed and
  store-center pinning are exactly this (memories: distant-chunk-vanish, walking-limbo) — port them
  forward into the ported `ClientWorldLoader` (chunk-loading #45/#46; current-mod-core §5).
- **R13g. Dynamic dimensions (DimLib) have no 26.2 form.** UNKNOWN-NEEDS-DESIGN at the loader level;
  blocks only OPTIONAL features (dim stack, alt dims); the dim-int-id map works for static dims without
  the event (platform-compat §4; network F10; chunk-loading #54). Alt-dims worldgen additionally needs
  `NoiseBasedChunkGenerator` definalized (now `final`, `NoiseBasedChunkGenerator.java:50` — AW+AT) and
  a re-derivation of the deleted `NoiseRouterData.noNewCaves` (platform-compat cross-cut 4).
- **R13h. Fabric API v6 renames + multiloader routing.** `PayloadTypeRegistry.playS2C/C2S` →
  `clientboundPlay/serverboundPlay`; `createS2CPacket/createC2SPacket` **GONE** → construct the vanilla
  record packets directly (network headline 5, F1-F3). All Fabric touchpoints route through the
  existing `PlatformHelper` seam — never Fabric types in `common` (every slice's FABRIC table).
  IP's own event objects (`IPGlobal.*_EVENT`, `Portal.*_SIGNAL`) are NOT Fabric API and port as plain
  infrastructure once `Helper.createConsumerEvent` is loader-neutral (portal-core F5).
- **R13i. Fabulous/transparency suppression is an override to port, not a read.**
  `Minecraft.useShaderTransparency()` HEAD-cancel re-anchors onto
  `GameRenderState.useShaderTransparency()` (`state/GameRenderState.java:17-19`; ducks G10) — easy to
  silently drop from the checklist.
- **R13j. `WorldInfoSender` shrinks to weather-only — a FORCED deviation.**
  `ClientboundSetTimePacket` is now clock-map based with no per-dim daylight boolean
  (`ClientboundSetTimePacket.java:14`; `GameRules.RULE_DAYLIGHT` gone) — document the intentional
  deletion of the time half (chunk-loading #34/#43, cross-cut 5). Weather's rain-flip broadcast is
  still un-dimensioned (`ServerLevel.java:785-795`), so IP's cross-dimension weather guard stays
  relevant (mixin-common §1 MixinLevel).
- **R13k. Camera is pull-model with hidden gates.** `Camera.setup(...)` gone → `setLevel/setEntity` +
  `update(DeltaTracker)` (`Camera.java:93-112,411,491`); a mod-built Camera must set the private
  `initialized` flag or `getFluidInCamera()` silently returns NONE (`Camera.java:437-440`;
  render-sub G1) — the mod's `CameraInvokerMixin` already exposes it. The view-rotation hook must NOT
  wrap `Matrix4f.rotation` inside `getViewRotationMatrix` (dirty-flag cached, `Camera.java:385-389`) —
  post-process `cameraState.viewRotationMatrix` after extract instead (render-sub C2; mixin-client §10 ⑪).

---

## VERDICT: can the render cutover be staged, or must it be atomic?

**Staged at the substrate boundary; atomic at the driver core. Three stages, of which the middle one is
a single non-divisible cutover.**

Evidence, from the disposition audit (`current-mod-render.md`) plus IP ground truth:

1. **The 26.2 substrate stays put and is separable (stage 1 — land and verify before any cutover).**
   41 of 63 audited render classes/mixins are KEEP/PORT-FORWARD: the stencil FBO chain
   (`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin`), the extractor plumbing
   (`LevelExtractorAccessor`, `ClientLevelExtractorAccessor`, flash-bridge, createRegion budget), the
   clip-upload hook (`GlCommandEncoderClipMixin`), shader-source injection
   (`ShaderManagerCompilationCacheMixin`), `DimensionRenderHelper`, `PortalRenderBuffersPool`,
   `VisibleSectionDiscovery`, `FrontClipping`, `PortalRenderTypes`. These are exactly the mechanisms the
   ported IP classes call into, and they are runtime-proven today under block portals. Preparatory
   refactors — re-homing the `GameRendererPortalPrepareMixin` upkeep (A4), relocating the pre-render
   pump (R2), landing `q_misc_util` and the context_management classes that have no portal-representation
   coupling — can all ship and soak while block portals still serve players.

2. **The driver core is atomic.** IP's renderer discovers portals by iterating
   `world.entitiesForRendering()` for `Portal` instances plus `GlobalPortalStorage`
   (`IP:imm_ptl/core/render/renderer/PortalRenderer.java:84-110` — verified directly for this doc), and
   its view-area mesh is shape-polymorphic off the Portal entity (`Portal.renderViewAreaMesh` →
   `PortalShape.renderViewAreaMesh`, `IP:imm_ptl/core/portal/Portal.java:877-889`). The mod's current
   `StencilPortalRenderer`/`PortalShapeRenderer`/`PortalContextSwitch` trio consumes block-portal
   geometry and FBO-identity discovery. Neither renderer can consume the other's portal representation,
   the portal representation is global per world (a world is block-portal or entity-portal, not both per
   portal), and the shared mutable seams — one `LevelRenderState`, one `visibleSections` list, one fog
   UBO slot, one stencil choreography — forbid two active portal-render drivers in one frame. Therefore
   the swap of {Portal entity + `PortalSyncPacket` sync, `PortalRenderer` + `RendererUsingStencil`,
   `ViewAreaRenderer`, `MyGameRenderer` + context_management, `FrustumCuller`, `TransformationManager`'s
   crossing math} is **one cutover**, gated by the briefing's 12-point regression checklist. Anyone
   proposing "port the stencil renderer first, keep block discovery" should note the discovery output
   type (`Portal` with `PortalShape`/`UnilateralPortalState`/transforms) is the input type of everything
   downstream.
   *Mitigation so "atomic" ≠ "big-bang blind":* the mod has already proven flag-gated dual render paths
   on 26.2 (`STENCIL_DIRECT`). Build the entity-portal driver behind a flag and exercise it against
   manually-spawned Portal entities in a test world while the block path still serves normal play — the
   atomicity is in the user-facing switch, not the development process.

3. **A genuine periphery can trail (stage 3).** `CrossPortalEntityRenderer` (R3 — the largest unknown,
   and additive: the mod ships nothing equivalent today, so its absence at cutover is status quo, not
   regression), `GuiPortalRendering`, `OverlayRendering`, mirrors/`BreakableMirror`, the FBO fallback
   renderer + renderMode config (AMBIGUOUS A1), IP's view-bob distance scaling (A2), wand/debug overlays
   (Gizmos), and portal-animation view refinements each land after the core cutover without invalidating it.

**Gating dependencies:** two items must be design-complete BEFORE the atomic commit — R1's seaLevel
protocol addition (secondary client worlds will not construct without it) and R2's frame-phase anchor
decision (the seamlessness of the crossing frame). R3 (per-entity clipping) is explicitly allowed to
trail. R5's reversed-Z sign flips and R4's ViewArea decision are inside the atomic commit's scope and
must be in its spec.
