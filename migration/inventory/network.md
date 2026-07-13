# IP Subsystem Inventory — Networking (core + q_misc_util)

Slice owner files:
- `qouteall/imm_ptl/core/network/` (4 files): `ImmPtlNetworking`, `ImmPtlNetworkConfig`, `PacketRedirection`, `PacketRedirectionClient`
- q_misc_util networking: `MiscNetworking`, `ImplRemoteProcedureCall`, `api/McRemoteProcedureCall`, `dimension/DimensionIntId`, `dimension/DimIntIdMap`, `dimension/DimensionIdRecord` (deprecated shim), `mixin/dimension/MixinPlayerList_Misc`, `mixin/MixinMinecraftServer_Misc`, plus entry points `MiscUtilModEntry` / `MiscUtilModEntryClient`
- Supporting mixins/ducks read for this slice: `MixinServerGamePacketListenerImpl_Redirect`, `MixinClientboundCustomPayloadPacket`, `MixinMinecraft_RedirectedPacket`, `IEServerConfigurationPacketListenerImpl`, `IECustomPayloadPacket`, `IEClientPacketListener_Misc`, `IEMinecraftServer_Misc`

All paths below are relative to `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/` unless absolute. IP source is Fabric, declared for MC `["1.21", "1.21.1"]` in `src/main/resources/fabric.mod.json` (`"minecraft": ["1.21", "1.21.1"]`) — **not** 1.21.3 as commonly stated; keep this in mind when diffing against vanilla.

---

## 1. Overview

This slice is the entire wire protocol of Immersive Portals. It has four pillars:

1. **Dimension integer-id sync** (q_misc_util): the server assigns a stable per-server `int` to every dimension (`DimIntIdMap`), syncs the full map (plus a dimension-id→dimension-type map) to each client at login and on dynamic dimension add/remove (`MiscNetworking.DimIdSyncPacket`). Every other packet in the mod carries dimensions as these ints, because (a) it is compact and (b) the deserialization context has no `MinecraftServer` access (`PacketRedirection.java:244`).
2. **Packet redirection** (`PacketRedirection` / `PacketRedirectionClient`): the mechanism that makes vanilla S2C *game* packets dimension-aware. Any vanilla packet that concerns a non-player-dimension world is wrapped into a tiny custom payload `i:r` = `(dimIntId, innerPacket)`; the client unwraps it on the render thread and handles the inner packet with `ClientWorldLoader.withSwitchedWorldFailSoft` so vanilla handler code operates on the correct `ClientLevel`. This is the load-bearing trick that lets IP reuse ALL vanilla sync logic (chunks, entities, block updates, time/weather, sounds) across dimensions.
3. **Gameplay packets** (`ImmPtlNetworking`): exactly three — C2S teleport confirmation, S2C global-portal storage sync, S2C portal entity spawn/update sync (the replacement for `ClientboundAddEntityPacket` for portals).
4. **Version handshake + RPC** : a configuration-phase version check that can disconnect mismatched clients (`ImmPtlNetworkConfig`), and a reflection-based remote-procedure-call facility (`ImplRemoteProcedureCall` behind the public `McRemoteProcedureCall` API) used by ~15 call sites (wand, dim stack, block manipulation across portals, perf info, debug commands) so features don't need bespoke packets.

Architecturally, this slice sits *under* everything else: chunk_loading, entity sync, teleportation, global portals, and block manipulation all send through `PacketRedirection`; the portal entity itself serializes through `ImmPtlNetworking.PortalSyncPacket`; and every dimension mentioned on the wire round-trips through `DimensionIntId`.

---

## 2. Class-by-class inventory

### 2.1 `imm_ptl/core/network/ImmPtlNetworking.java` (~269 LOC, common + client handler)

Container for the three gameplay packets. No state besides a `Logger`.

**`TeleportPacket`** (record, C2S) — `ImmPtlNetworking.java:45-90`
- Fields: `int dimensionId` (dimension the player crossed FROM), `Vec3 eyePosBeforeTeleportation`, `UUID portalId`.
- Wire: varint + 3 doubles + UUID (`write`, `ImmPtlNetworking.java:68-74`).
- Type id: `imm_ptl:teleport` (`ImmPtlNetworking.java:50`).
- Sent by: client immediately after client-side teleport, `ClientTeleportationManager.java:372-378` (`player.connection.send(ClientPlayNetworking.createC2SPacket(new TeleportPacket(PortalAPI.clientDimKeyToInt(fromDimension), thisTickEyePos, portal.getUUID())))`).
- Handled by: `handle(ServerPlayer)` → `ServerTeleportationManager.of(server).onPlayerTeleportedInClient(player, dim, eyePos, portalId)` (`ImmPtlNetworking.java:76-84`; handler body at `teleportation/ServerTeleportationManager.java:160`). Dimension int decoded via `PortalAPI.serverIntToDimKey` (`ImmPtlNetworking.java:77`).
- Thread: registered via `ServerPlayNetworking.registerGlobalReceiver` (`ImmPtlNetworking.java:251-254`); Fabric play-payload handlers run **on the server thread** (fabric-networking-api-v1 4.3.1 `ServerPlayNetworking.java:304` "This is called on the server thread").

**`GlobalPortalSyncPacket`** (record, S2C) — `ImmPtlNetworking.java:93-127`
- Fields: `int dimensionId`, `CompoundTag data` (the whole `GlobalPortalStorage` save NBT).
- Wire: varint + NBT (`ImmPtlNetworking.java:111-114`). Type id: `imm_ptl:upd_glb_ptl` (`ImmPtlNetworking.java:98`).
- Sent by: `GlobalPortalStorage.createSyncPacket` (`portal/global_portals/GlobalPortalStorage.java:148-157`), on player login for each non-empty per-world storage (`GlobalPortalStorage.java:135-146`) and to all players on data change (`syncToAllPlayers`, `GlobalPortalStorage.java:193-200`). Sent plain (`player.connection.send`), NOT redirected — dimension travels in-payload.
- Handled by: client `handle()` → `GlobalPortalStorage.receiveGlobalPortalSync(dim, data)` (`ImmPtlNetworking.java:117-121`; receiver kills old client global portals and rebuilds from NBT, `GlobalPortalStorage.java:326-346`).
- Thread: Fabric `ClientPlayNetworking.registerGlobalReceiver` (`ImmPtlNetworking.java:258-261`) → **render thread** (fabric-networking-api-v1 4.3.1 `ClientPlayNetworking.java:262` "This is called on the render thread").

**`PortalSyncPacket`** (record, S2C) — `ImmPtlNetworking.java:129-236`. This is the portal-entity spawn/update packet, IP's replacement for `ClientboundAddEntityPacket` (javadoc `ImmPtlNetworking.java:130-133`).
- Fields: `int id` (entity network id), `UUID uuid`, `EntityType<?> entityType`, `int dimensionId`, `double x, y, z`, `CompoundTag extraData` (full `addAdditionalSaveData` NBT of the portal).
- Wire: varint id, UUID, `ByteBufCodecs.registry(Registries.ENTITY_TYPE)` entity type, varint dim, 3 doubles, NBT (`ImmPtlNetworking.java:153-162`). Needs `RegistryFriendlyByteBuf` (registry codec). Type id: `imm_ptl:spawn_portal` (`ImmPtlNetworking.java:146`).
- NOTE the javadoc at `ImmPtlNetworking.java:132` says "This packet is redirected, so there is no need to contain dimension id" — yet the record **does** carry `dimensionId` and `handle()` uses it (`ImmPtlNetworking.java:183`). The client handler does not rely on the redirection context; the dimension in the payload is authoritative. Do not "simplify" either side away.
- Sent by: `Portal.createSyncPacket()` (`portal/Portal.java:904-919`) wrapped as `ServerPlayNetworking.createS2CPacket(...)` and returned from `Portal.getAddEntityPacket(ServerEntity)` (`Portal.java:897-902`) — i.e. it rides the **vanilla entity tracker pairing path** (`ServerEntity.addPairing`), whose `send` is redirected by `MixinServerEntity` (see §2.8). Also re-sent on demand by `Portal.reloadAndSyncToClient()` → `McHelper.sendToTrackers(this, packet)` (`Portal.java:519-529`; `McHelper.java:450-459` fetches the tracker and calls `broadcastAndSend`). Portal position changes are ONLY synced through this packet because vanilla move packets quantize to 1/4096 (`Portal.java:513-515`).
- Handled by: client `handle()` (`ImmPtlNetworking.java:179-230`): resolves dim via `PortalAPI.clientIntToDimKey`, gets `ClientLevel` from `ClientWorldLoader.getWorld(dimension)` (creates the client world if absent), then:
  - existing entity with same network id → validates UUID (`:190`) and entity type (`:195`) match (logs error and aborts otherwise) → `existingPortal.acceptDataSync(new Vec3(x,y,z), extraData)` (`:202`; `Portal.java:1738` sets pos + `readAdditionalSaveData`).
  - else → `entityType.create(world)`, `setId`, `setUUID`, `syncPacketPositionCodec(x,y,z)`, `moveTo(x,y,z)`, `portal.readPortalDataFromNbt(extraData)`, `world.addEntity(entity)` (`:206-221`), then eagerly creates the destination client world `ClientWorldLoader.getWorld(portal.getDestDim())` (`:223`) and fires `Portal.CLIENT_PORTAL_SPAWN_EVENT` (`:224`).
- Thread: Fabric client play receiver (`ImmPtlNetworking.java:263-266`) → render thread.

**Registration** — `init()` registers all three payload types in `PayloadTypeRegistry.playC2S()/playS2C()` plus the server receiver (`ImmPtlNetworking.java:238-255`); `initClient()` registers the two client receivers (`:257-267`).

Dependencies: `PortalAPI` (dim int mapping), `ClientWorldLoader`, `GlobalPortalStorage`, `ServerTeleportationManager`, `Portal`, `IPGlobal.clientPortalLoadDebug`.

### 2.2 `imm_ptl/core/network/ImmPtlNetworkConfig.java` (~318 LOC, common + client)

Configuration-phase (pre-play) version handshake using the 1.20.2+ configuration protocol.

- **`ModVersion`** record `(major, minor, patch)` with sentinel `OTHER = 0.0.0` for dev env (`ImmPtlNetworkConfig.java:38-69`); `isCompatibleWith` = same major+minor (`:66-68`).
- Static state: `public static ModVersion immPtlVersion` (filled from `O_O.getImmPtlVersion()`, which parses the Fabric mod container's semantic version, `platform_specific/O_O.java:142-163`); client-side `private static @Nullable ModVersion serverVersion` (`:306`), exposed via `doesServerHaveImmPtl()` (`:309-311`) — this is the flag other client code uses to know if the server runs IP.
- **`ImmPtlConfigurationTask`** (record implementing vanilla `ConfigurationTask`, type `iportal:config`, `:73-91`): its `start(Consumer<Packet<?>>)` sends `S2CConfigStartPacket(immPtlVersion)` via `ServerConfigurationNetworking.createS2CPacket` (`:79-85`).
- **`S2CConfigStartPacket`** (S2C, configuration phase; id `iportal:config_packet`; payload = 3 varints of ModVersion; `:93-131`). Client handler (`:116-125`): logs, stores `serverVersion`, replies with `C2SConfigCompletePacket(immPtlVersion, IPConfig.getConfig().clientTolerantVersionMismatchWithServer)` through `context.responseSender().sendPacket(...)`.
  - **Thread: netty event loop.** Fabric configuration-phase handlers are "executed on netty's event loops" (fabric-networking-api-v1 4.3.1 `ClientConfigurationNetworking.java:257`). The handler only touches this class's static field + sends — safe there, but on 26.2 any added logic must be thread-audited.
- **`C2SConfigCompletePacket`** (C2S, configuration phase; id `iportal:configure_complete`; payload = ModVersion + boolean `clientTolerantVersionMismatch`; `:133-200`). Server handler (`:157-194`): reads the connecting `GameProfile` via accessor mixin `IEServerConfigurationPacketListenerImpl.ip_getGameProfile()` (`mixin/common/other_sync/IEServerConfigurationPacketListenerImpl.java:9-12`, `@Accessor("gameProfile")` on `ServerConfigurationPacketListenerImpl`); if both sides are normal versions and major/minor differ and neither side is configured tolerant (`IPConfig.serverTolerantVersionMismatchWithClient`, `IPConfig.java:134`), calls `networkHandler.disconnect(Component.translatable("imm_ptl.mod_major_minor_version_mismatch", ...))` (`:176-180`); otherwise `networkHandler.completeTask(ImmPtlConfigurationTask.TYPE)` (`:193`). **Thread: netty event loop** (fabric-networking-api-v1 4.3.1 `ServerConfigurationNetworking.java:248`).
- **`init()`** (`:202-253`): stores version; registers both payloads in `PayloadTypeRegistry.configurationS2C()/configurationC2S()`; hooks `ServerConfigurationConnectionEvents.CONFIGURE` (`:215-247`) — if `ServerConfigurationNetworking.canSend(handler, S2CConfigStartPacket.TYPE)` (Fabric's sendable-channel sync = client has the mod) adds the task, else on dedicated servers either disconnects the client (config `serverRejectClientWithoutImmPtl`, default true, `IPConfig.java:136`; hard-coded literal message because the client lacks the translation, `:224-231`) or just warns; registers the server receiver (`:249-252`).
- **`initClient()`** (`:255-277`): registers the client configuration receiver; `ClientLoginConnectionEvents.INIT` resets `serverVersion = null` per connection (`:264-272`); `ClientPlayConnectionEvents.JOIN` → `onClientJoin()` (`:274-276`) which chat-warns about a server missing ImmPtl (`:279-282`, `:313-317` — via `Minecraft.getInstance().execute`) or a patch-version mismatch (`:283-302`).

Vanilla classes touched: `ConfigurationTask`(+`.Type`), `ServerConfigurationPacketListenerImpl` (`disconnect`, `completeTask`, `addTask` via Fabric, private `gameProfile` via accessor), `Component`/`MutableComponent`/`ChatFormatting`, `Minecraft.execute`. GameProfile is authlib.

### 2.3 `imm_ptl/core/network/PacketRedirection.java` (~290 LOC, common; `Payload.handle` client-only)

The server half of packet redirection. State: two `ThreadLocal`s —
- `serverPacketRedirection: ThreadLocal<ResourceKey<Level>>` (`PacketRedirection.java:50-51`) — the "force redirect" dimension for the current server-thread call stack;
- `forceBundle: ThreadLocal<ForceBundleCallback>` (`:60-61`) — optional collect-into-one-bundle mode.

Public API (called by other subsystems):
- `init()` — registers `Payload.TYPE`/`CODEC` in `PayloadTypeRegistry.playS2C()` only (`:63-65`).
- `withForceRedirect(ServerLevel, Runnable)` / `<T> withForceRedirectAndGet(ServerLevel, Supplier<T>)` (`:67-99`): sets the thread-local to `world.dimension()` for the duration of `func`, restoring the previous value in `finally`. Logs an error (with stack) if called off the level's owning thread — checked via duck `((IEWorld) world).portal_getThread() != Thread.currentThread()` (`:76-81`); this is IP's own "a mod is handling packets on the network thread" detector.
- `getForceRedirectDimension()` (`:105-108`): read by the send-side mixin.
- `sendRedirectedPacket(ServerGamePacketListenerImpl, Packet, ResourceKey<Level>)` (`:111-128`): if the force-redirect dim already equals the target dim, sends raw (the send mixin will wrap it); otherwise wraps explicitly. Avoids double wrapping.
- `createRedirectedMessage(MinecraftServer, ResourceKey<Level>, Packet)` (`:134-174`): the wrapper factory. Already-redirected packets pass through unchanged (`:140-143`, checked by `isRedirectPacket`, `:185-188` = `ClientboundCustomPayloadPacket` whose payload is a `PacketRedirection.Payload`). Asserts the input is not a `BundleDelimiterPacket` (`:145`). A `ClientboundBundlePacket` is NOT wrapped whole — each sub-packet is wrapped individually and re-bundled (`:146-159`), because vanilla's connection layer special-cases bundle packets. Everything else becomes `new ClientboundCustomPayloadPacket(new Payload(PortalAPI.serverDimKeyToInt(server, dimension), packet))` (`:162-172`; the cast from `Packet<ClientCommonPacketListener>` to `Packet<ClientGamePacketListener>` is deliberate contravariance, comment `:165-169`).
- `sendRedirectedMessage(ServerPlayer, ResourceKey<Level>, Packet)` (`:176-182`): wrap + `player.connection.send`.
- `validateForceRedirecting()` (`:130-132`): assertion used by `MixinServerEntity.onTick` (`mixin/common/entity_sync/MixinServerEntity.java:40-42`) to guarantee `ServerEntity.sendChanges` only ever runs inside a redirection scope.
- `withForceBundle(Supplier)` (`:190-227`): installs a callback that collects every packet sent through `ServerCommonPacketListenerImpl.send` (per listener, flattening nested bundles), then on exit sends each listener one `ClientboundBundlePacket` of the collected packets. Re-entrant (returns directly if already bundling, `:192-196`). **No call sites exist in the current source tree** (grep: only the declaration) — the interception hook in the mixin is live but the facility is dormant; port it anyway (fidelity), it is public API surface.
- **`Payload`** record `(int dimensionIntId, Packet<? extends ClientGamePacketListener> packet)` implements `CustomPacketPayload` (`:246-289`); id `i:r` — deliberately 3 chars because "most game packets sent are redirected" (`:45-48`).
  - Encoding (`:258-265`): varint dim id, then the inner packet encoded through `PLACEHOLDER_PROTOCOL_INFO.codec()`.
  - `PLACEHOLDER_PROTOCOL_INFO` (`:234-241`) = `GameProtocols.CLIENTBOUND_TEMPLATE.bind(argBuf -> (RegistryFriendlyByteBuf) argBuf)` — the **full vanilla clientbound-game protocol codec** (packet id + body), built by binding the template with an identity cast instead of a real `RegistryFriendlyByteBuf` factory (the buf passed in already is one). Comment `:233`: "Mojang's new networking abstraction made packet redirection more convoluted...". **This is the single most version-sensitive line in the slice** — on 26.2 the protocol-template/bind API must be re-verified against `mc262-ref`.
  - Decoding (`:268-275`): reads dim varint then `PLACEHOLDER_PROTOCOL_INFO.codec().decode(buf)` — this runs **on the netty thread** inside Fabric's payload decode; dim stays an int precisely because the client dim-map is only stable on the client thread (`:243-245` javadoc, and `PacketRedirectionClient.java:40-44`).
  - `handle(ClientGamePacketListener)` (client, `:277-283`) → `PacketRedirectionClient.handleRedirectedPacket(dimensionIntId, packet, listener)`.

Dependencies: `PortalAPI` (dim↔int), `IEWorld` duck, `McHelper.newResourceLocation`.

### 2.4 `imm_ptl/core/network/PacketRedirectionClient.java` (~116 LOC, client only)

The client half. State: `clientTaskRedirection: ThreadLocal<ResourceKey<Level>>` (`PacketRedirectionClient.java:32-33`) — non-null while a redirected packet (or a task spawned by one) is being processed; `getIsProcessingRedirectedMessage()` (`:35-37`).

- `handleRedirectedPacket(int dimensionIntId, Packet, ClientGamePacketListener)` (`:45-77`): designed to be **first called on the netty thread** (javadoc `:39-44`). If `minecraft.isSameThread()`: resolve `DimensionIntId.getClientMap().fromIntegerId(dimensionIntId)` (only safe on the client thread), push `clientTaskRedirection`, and run `packet.handle(handler)` inside `ClientWorldLoader.withSwitchedWorldFailSoft(dimension, ...)` (drops the packet with an error log if the client world doesn't exist — `ClientWorldLoader.java:592-598`), restoring the thread-local in `finally`. If NOT on the client thread: `minecraft.execute(() -> handleRedirectedPacket(...))` — re-submits itself (`:70-76`).
- `old_handleRedirectedPacket` (`:88-115`): deprecated pre-int-id variant; keep-or-drop is a fidelity question for the port lead (it is dead code — no callers found).
- Javadoc at `:79-87` documents the interplay: inner vanilla packets that call `PacketUtils.ensureRunningOnSameThread` would re-submit to `Minecraft`; the resubmitted task is re-wrapped with the redirection by `MixinMinecraft_RedirectedPacket` (see §2.7), so the dimension context survives the hop. Mod packets that use `Minecraft.execute` get the same treatment.

### 2.5 `q_misc_util/MiscNetworking.java` (~149 LOC, common + client handler)

One packet: **`DimIdSyncPacket`** (record, S2C, play phase, id `imm_ptl:dim_int_id_sync`, `MiscNetworking.java:36-132`).
- Fields: `CompoundTag dimIntIdTag` (the `DimIntIdMap` as NBT: `{"intids": {"<dim id>": int, ...}}`), `CompoundTag dimTypeTag` (`{"<dim id>": "<dimension type id>", ...}`).
- Built by `createFromServer(MinecraftServer)` (`:50-79`): serializes `DimensionIntId.getServerMap(server).toTag(dim -> true)`; then for every `ServerLevel` maps its dim id → dim type id via `server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).getKey(world.dimensionType())`, falling back to `BuiltinDimensionTypes.OVERWORLD.location()` with an error log if the type isn't registered (`:64-70`). `createPacket(server)` wraps in `ServerPlayNetworking.createS2CPacket` (`:81-85`).
- Sent: (1) during `PlayerList.placeNewPlayer`, injected just before the `ClientboundChangeDifficultyPacket` constructor — i.e. within the join packet burst, **before** any portal/chunk payloads (`q_misc_util/mixin/dimension/MixinPlayerList_Misc.java:15-31`); (2) to every online player whenever dimensions dynamically change (`dimension/DimensionIntId.java:123-127`).
- Handled: client `handle()` (`:99-126`): `DimensionIntId.clientRecord = DimIntIdMap.fromTag(dimIntIdTag)`; rebuilds `ClientWorldLoader.dimIdToDimTypeId` as an `ImmutableMap<ResourceKey<Level>, ResourceKey<DimensionType>>` (`:104-121`) — this map is what lets `ClientWorldLoader` construct remote `ClientLevel`s with the right dimension type without the server sending a login packet for them.
- Thread: Fabric client play receiver (`:135-142`) → render thread. Server side only registers the payload type (`:144-148`) — there is no C2S direction.

### 2.6 q_misc_util dimension-id classes

**`dimension/DimIntIdMap.java`** (~160 LOC, common, pure data): bidirectional map `Object2IntOpenHashMap<ResourceKey<Level>> toIntegerId` / `Int2ObjectOpenHashMap<ResourceKey<Level>> fromIntegerId` + `maxId` (`DimIntIdMap.java:22-24`). `MISSING_ID = Integer.MIN_VALUE` default (`:20`). Throwing lookups `fromIntegerId(int)` (`:43-51`) / `toIntegerId(key)` (`:58-66`) plus nullable variant (`:53-56`). `add` rejects duplicates on either side (`:68-82`). `removeUnused(Set)` (`:93-102`). NBT round-trip `fromTag`/`toTag` under key `"intids"` (`:112-143`, uses `Helper.dimIdToKey(String)`, `q_misc_util/Helper.java:508`). `getNextIntegerId() = maxId + 1` (`:149-151`) — **freed ids are never reused within a server run** (maxId only grows), which keeps in-flight packets unambiguous across dynamic dimension unload.

**`dimension/DimensionIntId.java`** (~129 LOC, common + client): lifecycle owner of the maps.
- Server map lives per-server in `IPPerServerInfo.dimIntIdMap` (`DimensionIntId.java:67-72`; field `imm_ptl/core/IPPerServerInfo.java:15`). Initialized in `onServerStarted` (`:74-89`) — called from `MixinMinecraftServer_Misc` at `MinecraftServer.createLevels` RETURN (`q_misc_util/mixin/MixinMinecraftServer_Misc.java:58-61`). Fixed ids: overworld=0, nether=-1, end=1 (`fillInVanillaDimIds`, `:91-101`); all other dims get `getNextIntegerId()` in `getAllLevels()` order (`:79-84`).
- Dynamic dimensions: `init()` (`:31-44`) registers on **DimLib**'s `DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` with a custom early phase `iportal:early_phase` ordered before default ("make sure that dimension int id updates before global portal storage update", `:32-36`). `onServerDimensionChanged` (`:103-128`) adds new dims, `removeUnused` over current `server.levelKeys()` (always retaining the three vanilla keys, `:112-119`), then broadcasts `DimIdSyncPacket` to all players.
- Client: `public static DimIntIdMap clientRecord` (`:29`), cleared on `IPCGlobal.CLIENT_EXIT_EVENT` (`:46-54`); `getClientMap()` hard-validates non-null with the message "This should not be used in networking thread." (`:59-65`).

**`dimension/DimensionIdRecord.java`** (18 LOC, `@Deprecated`): Polymer-compat shim; `getDim(int)` delegates to the server map via `MiscHelper.getServer()` (`DimensionIdRecord.java:12-18`). Keep only if Polymer compat matters.

The public int↔key API used by everything else is on **`PortalAPI`**: `clientDimKeyToInt`/`clientIntToDimKey` (client-thread only) and `serverDimKeyToInt`/`serverIntToDimKey` (`imm_ptl/core/api/PortalAPI.java:160-178`).

### 2.7 Redirection mixins (send side, receive side, task side)

**`mixin/common/entity_sync/MixinServerGamePacketListenerImpl_Redirect.java`** (mixin into `ServerCommonPacketListenerImpl`, despite the name):
- `@ModifyVariable` at HEAD of `send(Packet, PacketSendListener)` (`MixinServerGamePacketListenerImpl_Redirect.java:23-38`): if `getForceRedirectDimension() != null`, replaces the outgoing packet with `createRedirectedMessage(server, dim, packet)`. This is what makes `withForceRedirect` blanket-wrap *every* packet sent inside the scope (chunk data, light, block entities, entity spawns...).
- `@Inject` before `Connection.send(Packet, PacketSendListener, boolean)` (`:40-60`): if a force-bundle callback is installed, diverts the packet into it and cancels the real send.

**`mixin/common/networking/MixinClientboundCustomPayloadPacket.java`** (client receive side): HEAD-inject into `ClientboundCustomPayloadPacket.handle(ClientCommonPacketListener)` (`MixinClientboundCustomPayloadPacket.java:24-37`); if the payload is a `PacketRedirection.Payload` and the listener is a `ClientGamePacketListener`, calls `redirectPayload.handle(...)` and cancels — running **before Fabric API's own custom-payload handling** (comment `:23`). Implements empty marker duck `IECustomPayloadPacket` (`ducks/IECustomPayloadPacket.java:3-4`).
- Threading: vanilla invokes `Packet.handle` on the **netty thread**; `handleRedirectedPacket` then self-resubmits to the render thread (§2.4). Because the inject cancels unconditionally once matched, the vanilla `ensureRunningOnSameThread` re-queue never runs for the outer packet — the re-queue is done by IP itself via `minecraft.execute`.
- **26.2 PORT FLAG (isSameThread / double-delivery):** in IP 1.21 this HEAD inject fires exactly once per packet (netty thread), and single delivery is guaranteed by `ci.cancel()` + IP's own re-queue. On this project's 26.2 base, packet-handler HEAD injects are known to run **twice** (netty pre-pass + main-thread pass, with the re-queue throw skipping RETURN-phase cleanup — see MEMORY: respawn-mislabel). If the same double-invocation applies to `ClientboundCustomPayloadPacket.handle`, this mixin as written would call `handleRedirectedPacket` twice: the netty pass schedules a main-thread task AND the main pass handles directly → **the inner packet is handled twice**. The 26.2 port of this mixin therefore needs an explicit thread guard (e.g. only intercept on the netty pass, or dedupe). Same audit applies to any handler below that is wired through raw packet `handle` rather than Fabric receivers; all Fabric-receiver-based handlers (`ImmPtlNetworking`, `MiscNetworking`, RPC) get single main-thread delivery from Fabric itself.

**`mixin/client/sync/MixinMinecraft_RedirectedPacket.java`** (mixin into `Minecraft`):
- HEAD-inject into `Minecraft.wrapRunnable(Runnable)` (`MixinMinecraft_RedirectedPacket.java:23-38`): if `clientTaskRedirection` is set on the *submitting* thread, returns a runnable that re-enters `ClientWorldLoader.withSwitchedWorldFailSoft(redirectedDimension, runnable)` — so `Minecraft.execute` calls made during redirected handling stay dimension-correct.
- Overrides `scheduleExecutables()` (`:40-59`, `@IPVanillaCopy`): when on-thread AND currently processing a redirected message, returns false so the task executes **inline** instead of being queued — "Make sure that the redirected packet handling won't be delayed" (`:41-46`). On 26.2, verify `Minecraft`'s event-loop still routes through `wrapRunnable`/`scheduleExecutables` (both are `ReentrantBlockableEventLoop`/`BlockableEventLoop` members).

### 2.8 Redirection producers in other slices (consumer map, for cross-reference)

Every producer wraps vanilla S2C game packets; all are outside this slice but define the redirection protocol's real traffic:
- Entity tracking: `MixinTrackedEntity` wraps `TrackedEntity.broadcast` sends in `withForceRedirect` (`mixin/common/entity_sync/MixinTrackedEntity.java:61-78`), `broadcastAndSend` via `sendRedirectedPacket` (`:80-94`), and add/remove pairing inside `withForceRedirect` (`:164-187`); spawn-packet re-send builds `createRedirectedMessage` directly (`:245-260`). `MixinServerEntity` redirects `addPairing`/`removePairing`/`broadcastAndSend` sends (`mixin/common/entity_sync/MixinServerEntity.java:44-90`) and asserts a redirect scope exists during `sendChanges` (`:40-42`).
- Whole-tick scopes: `EntitySync.update/tick` wraps per-world entity tracking in `withForceRedirect(world, ...)` (`chunk_loading/EntitySync.java:27-40`, `:50-60`).
- Chunk sending: `PlayerChunkLoading.sendChunkPacket` wraps `ClientboundLevelChunkWithLightPacket` send (`chunk_loading/PlayerChunkLoading.java:193-210`); `MixinChunkHolder.modifyPacket` wraps every `ChunkHolder.broadcast` packet (`mixin/common/chunk_sync/MixinChunkHolder.java:29-42`); `ImmPtlChunkTracking` sends redirected `ClientboundForgetLevelChunkPacket` on unwatch/removal (`chunk_loading/ImmPtlChunkTracking.java:262-272`, `:505-511`, `:529-533`).
- World info: `WorldInfoSender.sendWorldInfo` sends redirected `ClientboundSetTimePacket` + `ClientboundGameEventPacket` (rain/thunder) for every OTHER dimension the player watches (`chunk_loading/WorldInfoSender.java:47-94`).
- Broadcast overrides: `MixinPlayerList` re-implements `broadcastAll(packet, dimension)` (`mixin/common/other_sync/MixinPlayerList.java:65-82`) and `broadcast(...)` for sounds/events near a position (`:104-135`) using redirected sends keyed by IP's own watch records.
- Block manipulation: `BlockManipulationServer.withRedirect` wraps remote-dim interaction processing (`block_manipulation/BlockManipulationServer.java:187-201`) and sends redirected `ClientboundBlockUpdatePacket` rejections (`:272-286`).
- Public API: `PortalAPI.sendPacketToEntityTrackers` (`api/PortalAPI.java:180-191`).
- Compat: `MixinCardinalCompComponentKey` redirects Cardinal Components sync packets (`compat/mixin/cardinal_comp/MixinCardinalCompComponentKey.java:37`, `:45`).

### 2.9 `q_misc_util/ImplRemoteProcedureCall.java` (~476 LOC, common + client)

Reflection-based RPC. Two payloads, symmetric:

**`C2SRPCPayload`** (id `iportal:remote_c2s`, `ImplRemoteProcedureCall.java:138-231`) and **`S2CRPCPayload`** (id `iportal:remote_s2c`, `:233-315`). Record fields: `boolean deserializeSuccess, @Nullable String methodPath, @Nullable Method method, @Nullable List<Object> args` — `method`/`deserializeSuccess` are receiver-side only; the sender constructs `(true, methodPath, null, List.of(arguments))` (`:386-406`).
- Wire format: UTF `methodPath`, then each argument serialized in order (`write`, `:187-194` / `:279-286`). **No arg count and no type tags on the wire** — the receiver derives argument types from the resolved method's `getGenericParameterTypes()` (`:164`, `:258`). C2S skips parameter 0 (the `ServerPlayer`, injected at invoke time; `:169-173`, `:210-215`).
- Decode happens **inside the codec** (i.e. on the netty thread): `read` resolves the method (`getMethodByPath`, cached in a `ConcurrentHashMap`, `:421-433`; `findMethodByPath` requires the class path to contain `"RemoteCallable"` as a security gate, retries `a.b.Outer.Inner` as `a.b.Outer$Inner`, and requires a public method, `:435-474`) and deserializes args. Any exception is swallowed into a `deserializeSuccess=false` payload (rate-limited log, `:177-184`) so malformed input cannot kill the connection at decode time.
- Argument serialization (`serializeArgument`, `:357-375` / `deserializeArgument`, `:347-355`): a fixed typed table for `ResourceLocation`, `ResourceKey` (written as its location; deserialized only as `ResourceKey<Level>` or `ResourceKey<Biome>` via Gson `TypeToken`s, `:106-117`), `BlockPos`, `Vec3`, `UUID`, `Block`/`Item` (registry byNameCodec via JSON), `BlockState`/`ItemStack` (their `CODEC` via JSON string, `:86-89`), `CompoundTag`, `Component` (`ComponentSerialization.TRUSTED_STREAM_CODEC`, `:91-93`), `DQuaternion`, `byte[]` (`:101`; used to tunnel whole vanilla C2S packets — see `MixinMultiPlayerGameMode.ip_redirectPacket`, `mixin/client/interaction/MixinMultiPlayerGameMode.java:115-139`). Serializer lookup falls back to `isAssignableFrom` scan (`:361-366`, e.g. subclasses), then to **Gson JSON string** for everything else (`:368-372`), with `MiscHelper.gson` (`:67`). Codec-based types go over the wire as JSON strings (`serializeByCodec`/`deserializeByCodec`, `:339-345`, `:377-383`).
- Handling: C2S `handle(ServerPlayNetworking.Context)` (`:196-225`) invokes `method.invoke(null, [player, ...args])` on the **server thread**; failure → rate-limited log + red chat message to the sender (`:415-419`). S2C `handle(ClientPlayNetworking.Context)` (`:288-309`) invokes `method.invoke(null, args)` on the **render thread**; failure → red chat line (`:408-413`).
- Registration: `init()` registers both payload types + server receiver (`:317-330`); `initClient()` the client receiver (`:332-337`).

### 2.10 `q_misc_util/api/McRemoteProcedureCall.java` (~159 LOC, public API)

Thin facade: `tellClientToInvoke(ServerPlayer, methodPath, Object...)` → `player.connection.send(ImplRemoteProcedureCall.createS2CPacket(...))` (`McRemoteProcedureCall.java:106-111`); `tellServerToInvoke(methodPath, Object...)` (client) → `Minecraft.getInstance().getConnection().send(...)` (`:146-152`); plus `createPacketToSendToClient/Server` non-sending variants (`:116-120`, `:154-158`). The big class javadoc (`:12-81`) is the contract: supported types, "class path must contain RemoteCallable", first C2S arg = sender player, invoked on the client render thread / server thread respectively.

Known RPC users in-tree (19 files match `RemoteCallable`): `ClientTeleportationManager.RemoteCallables` (`teleportation/ClientTeleportationManager.java:693`), `ImmPtlChunkTracking.RemoteCallables.acceptClientPerformanceInfo` (`chunk_loading/ImmPtlChunkTracking.java:660`), `BlockManipulationServer.RemoteCallables.processPlayerActionPacket/processUseItemOnPacket` (`block_manipulation/BlockManipulationServer.java:136-144`), wand classes, `DimStackManagement`, `PortalCommand`, debug commands, `ExampleGuiPortalRendering`.

### 2.11 Entry/registration classes

- `q_misc_util/MiscUtilModEntry.java` (Fabric `ModInitializer`): `ImplRemoteProcedureCall.init(); MiscNetworking.init(); DimensionIntId.init();` (`MiscUtilModEntry.java:8-15`).
- `q_misc_util/MiscUtilModEntryClient.java` (Fabric `ClientModInitializer`): `ImplRemoteProcedureCall.initClient(); MiscNetworking.initClient();` (`MiscUtilModEntryClient.java:7-12`). (`DimensionIntId.initClient()` is called from `IPModMainClient.java:134` instead.)
- `imm_ptl/core/IPModMain.init()`: `ImmPtlNetworking.init(); ImmPtlNetworkConfig.init(); PacketRedirection.init();` (`IPModMain.java:67-69`); `IPModMainClient`: `ImmPtlNetworking.initClient(); ImmPtlNetworkConfig.initClient();` (`IPModMainClient.java:125-126`).
- Entry points are wired in `fabric.mod.json` (`entrypoints.main` includes `qouteall.q_misc_util.MiscUtilModEntry`, `entrypoints.client` includes `MiscUtilModEntryClient`).
- Mixin registration: `q_misc_util.mixins.json` lists `MixinMinecraftServer_Misc`, `dimension.MixinPlayerList_Misc`, client `client.IEClientPacketListener_Misc` (accessor `ip_setLevels(Set<ResourceKey<Level>>)` on `ClientPacketListener.levels` — used by the dimension-change client logic, not by packets directly, `q_misc_util/mixin/client/IEClientPacketListener_Misc.java:11-15`), plus non-networking `IELevelStorageAccess_Misc` and `client.MixinGui_Overlay` (custom text overlay — out of this slice). The redirection mixins live in `imm_ptl.mixins.json`'s package.
- `MixinMinecraftServer_Misc` also captures the server instance into `MiscGlobals.refMinecraftServer` (WeakReference) at constructor RETURN (`MixinMinecraftServer_Misc.java:48-56`; `MiscGlobals.java:9-11`) — this is what `MiscHelper.getServer()` and thus the deprecated `DimensionIdRecord` use.

---

## 3. Mechanisms

### 3.1 Dimension int-id sync (the id handshake every packet depends on)

1. Server start: `MinecraftServer.createLevels` RETURN → `DimensionIntId.onServerStarted` builds the per-server `DimIntIdMap` (OW=0, nether=-1, end=1, others sequential) into `IPPerServerInfo.dimIntIdMap` (`DimensionIntId.java:74-89`).
2. Player join: `PlayerList.placeNewPlayer`, immediately before the `ClientboundChangeDifficultyPacket` is constructed, the server sends `DimIdSyncPacket` (`MixinPlayerList_Misc.java:15-31`). Ordering matters: it precedes global-portal sync (sent from `GlobalPortalStorage.onPlayerLoggedIn`) and any redirected packet, so the client can always decode `dimensionId` ints.
3. Dynamic dimension add/remove (DimLib event, early phase): map updated (ids never reused — `getNextIntegerId` is monotonic), full map re-broadcast to all players (`DimensionIntId.java:103-128`).
4. Client receives on render thread: replaces `DimensionIntId.clientRecord` wholesale and rebuilds `ClientWorldLoader.dimIdToDimTypeId` (dim → dimension type), enabling client-side construction of `ClientLevel`s for dimensions the vanilla login never mentioned (`MiscNetworking.java:99-126`).
5. Consumers translate through `PortalAPI` (`PortalAPI.java:160-178`). Client-side translation is client-thread-only; that is why `PacketRedirection.Payload` keeps the int until the handler is on the render thread (`PacketRedirectionClient.java:39-44`).

### 3.2 Packet redirection, server → client, end to end

Server side, two ways a packet gets wrapped:
- **Scoped**: `withForceRedirect(world, func)` sets a thread-local; while set, `MixinServerGamePacketListenerImpl_Redirect.modifyPacket` rewrites EVERY packet passing through `ServerCommonPacketListenerImpl.send(...)` into `ClientboundCustomPayloadPacket(Payload(dimInt, inner))` (`MixinServerGamePacketListenerImpl_Redirect.java:23-38`). Used to blanket entire vanilla subsystو runs (entity tracker tick, chunk send, pairing).
- **Point**: `sendRedirectedPacket` / `sendRedirectedMessage` / `createRedirectedMessage` wrap one packet explicitly, skipping the wrap when the active scope already matches (`PacketRedirection.java:111-128`) and passing through already-wrapped packets (`:140-143`) — no nesting, ever.
- Bundles are unwrapped, each sub-packet wrapped, then re-bundled (`:146-159`); a raw `BundleDelimiterPacket` is asserted against (`:145`).

Wire format of `i:r`: `varint dimIntId` + vanilla clientbound-game-protocol encoding (id+body) of the inner packet via `GameProtocols.CLIENTBOUND_TEMPLATE.bind(cast)` (`PacketRedirection.java:234-241`, `:258-275`).

Client side:
1. Netty thread decodes the payload (inner packet fully decoded here, via the same placeholder protocol).
2. Vanilla calls `ClientboundCustomPayloadPacket.handle(listener)` on the netty thread; the HEAD inject in `MixinClientboundCustomPayloadPacket` recognizes `Payload`, calls `Payload.handle(clientGamePacketListener)` and cancels (before Fabric API sees it) (`MixinClientboundCustomPayloadPacket.java:24-37`).
3. `PacketRedirectionClient.handleRedirectedPacket`: not on render thread → `minecraft.execute(self)`. On render thread → translate dim int (now safe), set `clientTaskRedirection`, and run `innerPacket.handle(handler)` inside `ClientWorldLoader.withSwitchedWorldFailSoft(dim, ...)` — vanilla handler code sees `mc.level` = the redirected dimension's `ClientLevel` (`PacketRedirectionClient.java:45-77`).
4. Escape hatches for nested scheduling: any `Minecraft.execute` performed by the inner handler is re-wrapped with the dimension by the `wrapRunnable` inject, and `scheduleExecutables()` returns false during redirected processing so vanilla's `ensureRunningOnSameThread` re-submission executes inline rather than being deferred a frame (`MixinMinecraft_RedirectedPacket.java:23-59`).

### 3.3 Portal entity sync

Spawn/update both travel as `PortalSyncPacket` (full NBT every time):
- Spawn: vanilla tracker pairing calls `Portal.getAddEntityPacket` → `createSyncPacket()` (a Fabric S2C custom-payload packet, `Portal.java:897-919`); the pairing send is redirected by `MixinServerEntity.onSendAddEntityPacket` (`MixinServerEntity.java:61-76`), so on the wire it is `i:r{dim, custom_payload{imm_ptl:spawn_portal{...}}}`.
- Update: `Portal.reloadAndSyncToClient()` (deferred to tick via `reloadAndSyncNextTick`, `Portal.java:937-941`) re-sends the same packet to trackers (`Portal.java:519-529`) — this is the ONLY position sync for portals; vanilla `ServerEntity` move packets are too coarse (1/4096) (`Portal.java:513-517`, `MixinServerEntity.java:93-95`).
- Client handle distinguishes update-vs-spawn by `world.getEntity(id)` and validates UUID/type on update (`ImmPtlNetworking.java:186-229`); on spawn it eagerly loads the destination client world and fires `CLIENT_PORTAL_SPAWN_EVENT`.
- Despawn: plain vanilla remove packet through the redirected tracker path (no custom packet).

### 3.4 Teleport confirmation (client-authoritative crossing)

Client teleports itself first (`ClientTeleportationManager`), then notifies: `TeleportPacket(fromDimInt, eyePosBeforeTeleport, portalUUID)` (`ClientTeleportationManager.java:372-378`). Server handler validates and mirrors the teleport (`ServerTeleportationManager.onPlayerTeleportedInClient`, `ServerTeleportationManager.java:160+`; rejects removed players `:166-169`). Runs on the server thread via Fabric receiver.

### 3.5 Version handshake (configuration phase)

Server `CONFIGURE` event → if the client declared the `iportal:config_packet` channel (Fabric sendable-channel sync), queue `ImmPtlConfigurationTask`; else disconnect/warn per config (`ImmPtlNetworkConfig.java:215-247`). Task start → `S2CConfigStartPacket(serverVersion)`. Client (netty thread) stores it and replies `C2SConfigCompletePacket(clientVersion, tolerantFlag)`. Server (netty thread) disconnects on major/minor mismatch unless either side is tolerant, else `completeTask` → configuration proceeds (`:157-194`). At play-phase JOIN the client warns in chat if `serverVersion` is null (vanilla server) or patch-differs (`:274-303`).

### 3.6 RPC

`McRemoteProcedureCall.tellClientToInvoke/tellServerToInvoke(methodPath, args...)` → payload = UTF method path + positionally serialized args; receiver resolves the static method by reflection (cached; class path must contain `RemoteCallable`), deserializes args by the method's declared parameter types, and invokes on the main thread of its side (C2S prepends the `ServerPlayer`). Decode errors are captured at codec level into a `deserializeSuccess=false` payload and surfaced as rate-limited red chat messages, never a kick (`ImplRemoteProcedureCall.java:156-185`, `:196-225`). The `byte[]` arg type lets the client tunnel re-encoded vanilla C2S packets for cross-portal interaction (`MixinMultiPlayerGameMode.java:115-139` → `BlockManipulationServer.RemoteCallables`, `BlockManipulationServer.java:136-144`).

---

## 4. MC API touchpoint list (deduplicated; verify each against 26.2)

Networking model (highest risk):
- `net.minecraft.network.protocol.common.custom.CustomPacketPayload` + `CustomPacketPayload.Type` (all 8 payloads)
- `net.minecraft.network.codec.StreamCodec.of(encoder, decoder)`; `ByteBufCodecs.registry(Registries.ENTITY_TYPE)` (`ImmPtlNetworking.java:156`, `:167`)
- `net.minecraft.network.FriendlyByteBuf`: `readVarInt/writeVarInt`, `readDouble/writeDouble`, `readUUID/writeUUID`, `readNbt/writeNbt`, `readUtf/writeUtf`, `readResourceLocation/writeResourceLocation`, `readBlockPos/writeBlockPos`, `readByteArray/writeByteArray`
- `net.minecraft.network.RegistryFriendlyByteBuf` (registry-aware payloads: `PortalSyncPacket`, RPC, redirection `Payload`)
- **`net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(bufUpgrader)` → `ProtocolInfo<ClientGamePacketListener>` → `.codec().encode/decode`** (`PacketRedirection.java:234-241`, `:258-275`) — encodes/decodes arbitrary game packets inside a payload; the protocol-template API shape must be re-derived from `mc262-ref`
- `net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket` — constructed directly (`PacketRedirection.java:172`), payload accessor (`:186-187`), and **mixin target** `handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V` (`MixinClientboundCustomPayloadPacket.java:24-28`)
- `net.minecraft.network.protocol.game.ClientboundBundlePacket` (ctor + `subPackets()`, `PacketRedirection.java:146-158`, `:219-225`), `net.minecraft.network.protocol.BundlePacket`, `BundleDelimiterPacket`
- `net.minecraft.network.protocol.Packet`, `PacketUtils.ensureRunningOnSameThread` (behavioral dependency, `PacketRedirectionClient.java:80`), `net.minecraft.network.PacketListener`, `PacketSendListener`
- Listener types: `ClientGamePacketListener`, `ClientCommonPacketListener`, `ServerCommonPacketListener`, `ClientPacketListener` (`handleAddEntity` referenced `ImmPtlNetworking.java:177`; private `levels` field accessor `IEClientPacketListener_Misc.java:13-14`; `handleBundlePacket` referenced `PacketRedirectionClient.java:83`)
- **Mixin target** `ServerCommonPacketListenerImpl.send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V` + inner call `Connection.send(Packet, PacketSendListener, boolean)` (`MixinServerGamePacketListenerImpl_Redirect.java:24`, `:42-46`) + shadowed `protected MinecraftServer server` field (`:20`)
- `ServerGamePacketListenerImpl.send(Packet)` / `.player` (`PacketRedirection.java:117-124`)
- Configuration protocol: `net.minecraft.server.network.ConfigurationTask` (+`.Type`, `start(Consumer<Packet<?>>)`), `ServerConfigurationPacketListenerImpl.disconnect(Component)` / `.completeTask(Type)` / private `gameProfile` field (accessor mixin)
- `ClientboundAddEntityPacket` (doc-reference analog only)

Threading / event loop:
- `Minecraft.getInstance()`, `.execute(Runnable)`, `.isSameThread()`, **mixin target** `Minecraft.wrapRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;`, override of `ReentrantBlockableEventLoop.scheduleExecutables()` / `.runningTask()` (`MixinMinecraft_RedirectedPacket.java:16-59`)
- `net.minecraft.util.thread.BlockableEventLoop` (javadoc contract)
- `MinecraftServer` extends `ReentrantBlockableEventLoop` (mixin superclass, `MixinMinecraftServer_Misc.java:28`)

Server / level lifecycle:
- **Mixin targets**: `MinecraftServer.<init>` RETURN + `MinecraftServer.createLevels(ChunkProgressListener)` RETURN (`MixinMinecraftServer_Misc.java:48-61`); `PlayerList.placeNewPlayer` @At INVOKE `ClientboundChangeDifficultyPacket.<init>(Difficulty,Z)` (`MixinPlayerList_Misc.java:15-21`)
- `MinecraftServer.getAllLevels()`, `.levelKeys()`, `.getPlayerList().getPlayers()`, `.registryAccess()`, `.isDedicatedServer()`
- `ServerLevel.dimension()`, `.dimensionType()`, `.getServer()`
- `Level.OVERWORLD/NETHER/END`; `ResourceKey.create(Registries.DIMENSION | DIMENSION_TYPE | BIOME, ...)`
- `RegistryAccess.registryOrThrow(Registries.DIMENSION_TYPE)`, `Registry.getKey`, `BuiltinDimensionTypes.OVERWORLD`
- `ServerPlayer.connection.send(Packet)`, `.server`, `.sendSystemMessage`; `ServerPlayer.getRemovalReason` (handler dependency)

Entity lifecycle (client spawn path, `ImmPtlNetworking.java:206-221`):
- `EntityType.create(Level)`, `Entity.setId(int)`, `.setUUID(UUID)`, `.syncPacketPositionCodec(x,y,z)`, `.moveTo(x,y,z)`, `.getId()`, `.getUUID()`, `.getType()`
- `ClientLevel.getEntity(int)`, `ClientLevel.addEntity(Entity)`

Data / serialization:
- `CompoundTag` (`getCompound`, `getAllKeys`, `getInt`, `contains`, `putString`, `getString`, `put`), `IntTag.valueOf`, `NbtUtils` (consumer side)
- `ComponentSerialization.TRUSTED_STREAM_CODEC` (`ImplRemoteProcedureCall.java:91-93`, `:128`)
- `BuiltInRegistries.BLOCK/.ITEM` `byNameCodec()`, `BlockState.CODEC`, `ItemStack.CODEC` (RPC arg codecs — **ItemStack.CODEC / BlockState JSON round-trip is a known churn area**)
- `Component.translatable/literal`, `MutableComponent`, `ChatFormatting`; `Minecraft.gui.getChat().addMessage`
- `Vec3`, `BlockPos`, `com.mojang.serialization.Codec` + `JsonOps.INSTANCE`

Fabric API (loader-specific; the multiloader port must map these):
- `PayloadTypeRegistry.playC2S()/playS2C()/configurationC2S()/configurationS2C()` `.register(TYPE, CODEC)`
- `ServerPlayNetworking.registerGlobalReceiver/createS2CPacket`; `ClientPlayNetworking.registerGlobalReceiver/createC2SPacket`
- `ServerConfigurationNetworking.createS2CPacket/canSend/registerGlobalReceiver` (+ `Context.networkHandler()`); `ClientConfigurationNetworking.registerGlobalReceiver` (+ `Context.responseSender()`)
- `ServerConfigurationConnectionEvents.CONFIGURE`; `ClientLoginConnectionEvents.INIT`; `ClientPlayConnectionEvents.JOIN`
- Thread contracts (fabric-networking-api-v1 4.3.1 sources): play receivers = main thread of side (`ClientPlayNetworking.java:262`, `ServerPlayNetworking.java:304`); configuration receivers = **netty event loop** (`ClientConfigurationNetworking.java:257`, `ServerConfigurationNetworking.java:248`)
- DimLib: `qouteall.dimlib.api.DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` (+ `addPhaseOrdering`, `DimensionIntId.java:33-43`); `FabricLoader.getModContainer("iportal")` version lookup (`O_O.java:142-163`)

---

## 5. Registration & wiring

| What | Where registered | When |
|---|---|---|
| `TeleportPacket`, `GlobalPortalSyncPacket`, `PortalSyncPacket` types + server receiver | `ImmPtlNetworking.init()` ← `IPModMain.java:67` | mod init (common) |
| client receivers for the two S2C gameplay packets | `ImmPtlNetworking.initClient()` ← `IPModMainClient.java:125` | client mod init |
| config payloads + CONFIGURE hook + server config receiver | `ImmPtlNetworkConfig.init()` ← `IPModMain.java:68` | mod init |
| client config receiver + login/join events | `ImmPtlNetworkConfig.initClient()` ← `IPModMainClient.java:126` | client mod init |
| redirection `Payload` type (S2C only) | `PacketRedirection.init()` ← `IPModMain.java:69` | mod init |
| RPC payloads + server receiver | `ImplRemoteProcedureCall.init()` ← `MiscUtilModEntry.java:10` | q_misc_util entrypoint |
| RPC client receiver | `ImplRemoteProcedureCall.initClient()` ← `MiscUtilModEntryClient.java:8` | q_misc_util client entrypoint |
| `DimIdSyncPacket` type | `MiscNetworking.init()` ← `MiscUtilModEntry.java:12` | q_misc_util entrypoint |
| `DimIdSyncPacket` client receiver | `MiscNetworking.initClient()` ← `MiscUtilModEntryClient.java:10` | q_misc_util client entrypoint |
| DimLib dynamic-dimension listener (early phase) | `DimensionIntId.init()` ← `MiscUtilModEntry.java:14` | q_misc_util entrypoint |
| client dim-map reset on exit | `DimensionIntId.initClient()` ← `IPModMainClient.java:134` (NOT the q_misc_util client entry) | client mod init |
| server dim map creation | `MixinMinecraftServer_Misc` @ `createLevels` RETURN | every server start |
| dim-id sync at login | `MixinPlayerList_Misc` @ `placeNewPlayer` | every player join |
| global-portal sync at login | `GlobalPortalStorage.onPlayerLoggedIn` (`GlobalPortalStorage.java:135`) | every player join (per non-empty world storage) |
| send-side auto-wrap + force-bundle interception | `MixinServerGamePacketListenerImpl_Redirect` (mixin on `ServerCommonPacketListenerImpl`) | passive, keyed on ThreadLocals |
| client unwrap of `i:r` | `MixinClientboundCustomPayloadPacket` (HEAD, before Fabric) | passive |
| client task re-wrapping + inline scheduling | `MixinMinecraft_RedirectedPacket` | passive |

No entity types, block entities, or tick hooks are registered by this slice; it is entirely payload registries + events + mixins. Everything is static — no instances to construct.

### Port-blocking notes (26.2)

1. **`GameProtocols.CLIENTBOUND_TEMPLATE` / `ProtocolInfo` bind-with-cast** (`PacketRedirection.java:234-241`) is the redirection wire format. 26.2's protocol/codec plumbing must be re-verified in `mc262-ref` before anything else in this slice.
2. **`MixinClientboundCustomPayloadPacket` needs an isSameThread/double-delivery guard on 26.2** (see §2.7) — in this repo's experience every packet-handler HEAD inject runs twice (netty pre-pass + main pass, RETURN cleanup skipped by the re-queue throw). IP 1.21 relies on single netty-thread invocation + `ci.cancel()`.
3. Fabric configuration receivers run on the **netty thread** — `ImmPtlNetworkConfig` handlers were written for that; if the port replaces Fabric networking, preserve (or consciously re-derive) the threading of each handler as tabulated in §2.
4. The existing project already ships equivalents of several of these mechanisms (redirection payload channel, dim sync) — this doc describes IP upstream verbatim, as the fidelity target.
