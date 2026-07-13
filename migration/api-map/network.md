# 26.2 API Map — Networking (core + q_misc_util)

**Inventory:** `migration/inventory/network.md` (touchpoints below cover its §4 list, one row each; supporting-mixin members from §2 included).
**26.2 ground truth:** decompiled vanilla at `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings). All 26.2 citations are relative to that root. IP citations are into `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`.
**Fabric-API ground truth:** fabric-networking-api-v1 **6.3.x** sources from the gradle cache (`fabric-networking-api-v1-6.3.3+72073ef09c-sources.jar`), the version family the 26.2 toolchain resolves; the current mod's working `FabricPlatformHelper` confirms the renames compile.
**Verdict counts:** 30 SAME · 14 CHANGED · 3 GONE (vanilla touchpoints) + 10 FABRIC-API/external rows (noted separately, 2 of them GONE-severity).

**Headline findings (read first):**
1. **The wire-format core survives.** `GameProtocols.CLIENTBOUND_TEMPLATE` still exists with the same `bind(Function<ByteBuf,B>)` → `ProtocolInfo.codec()` shape (`net/minecraft/network/protocol/game/GameProtocols.java:131`), so IP's `PLACEHOLDER_PROTOCOL_INFO` identity-cast bind (`PacketRedirection.java:239-241`) compiles unchanged. The single most version-sensitive line in the slice ports 1:1.
2. **The client packet re-queue moved off the Minecraft event loop.** `PacketUtils.ensureRunningOnSameThread` no longer takes a `BlockableEventLoop`; it schedules into a new `PacketProcessor` queue (`net/minecraft/network/PacketUtils.java:21-26`) owned by `Minecraft` (`net/minecraft/client/Minecraft.java:369,730,2922`) and drained in `runTick` **before** the event-loop `runAllTasks` (`Minecraft.java:1170-1172`). Consequences: (a) IP's `minecraft.execute` self-resubmit of redirected packets would land in a *different* queue than vanilla's re-queued packets → **cross-queue packet reordering** that did not exist on 1.21; (b) the `wrapRunnable` inject no longer sees vanilla packet re-queues at all. See the threading appendix (§A) for the faithful port shape.
3. **`ResourceLocation` is renamed `Identifier`** (`net/minecraft/resources/Identifier.java:18`), with `FriendlyByteBuf.readIdentifier/writeIdentifier` (`FriendlyByteBuf.java:577,581`), `CustomPacketPayload.Type(Identifier)` (`custom/CustomPacketPayload.java:56`), `ResourceKey.identifier()` replacing `.location()` (`resources/ResourceKey.java:64`). Cross-cutting over every payload id in the slice (`i:r`, `imm_ptl:*`, `iportal:*`).
4. **`PacketSendListener` is no longer the send-callback parameter type.** `ServerCommonPacketListenerImpl.send` and `Connection.send` now take netty `io.netty.channel.ChannelFutureListener` (`server/network/ServerCommonPacketListenerImpl.java:160`, `network/Connection.java:281`); `PacketSendListener` survives only as a static factory of `ChannelFutureListener`s (`network/PacketSendListener.java:10-34`). Both redirection-mixin injection descriptors must be rewritten.
5. **Fabric API v6 renamed the registration surface:** `PayloadTypeRegistry.playS2C()/playC2S()/configurationS2C()/configurationC2S()` → `clientboundPlay()/serverboundPlay()/clientboundConfiguration()/serverboundConfiguration()`; `ServerPlayNetworking.createS2CPacket`/`ClientPlayNetworking.createC2SPacket` are **gone** (construct `new ClientboundCustomPayloadPacket(payload)`/`new ServerboundCustomPayloadPacket(payload)` directly — both are records with public ctors — or use `PacketSender.createPacket`). Thread contracts are unchanged (play = main thread of side, configuration = netty event loop).
6. **`MixinMinecraftServer_Misc`'s two injection points both changed:** the `MinecraftServer` ctor has a new arg list (no more `ChunkProgressListenerFactory`; adds `Optional<GameRules>`, `LevelLoadListener`, `boolean`, `NotificationManager` — `server/MinecraftServer.java:311-323`) and `createLevels` is now **no-arg** (`MinecraftServer.java:421`). Also `ReentrantBlockableEventLoop`'s ctor gained a `boolean propagatesCrashes` (`util/thread/ReentrantBlockableEventLoop.java:6`), breaking both mixin dummy super-calls.
7. **`ServerPlayer.server` is now private** (`server/level/ServerPlayer.java:232`) — five call sites in this slice: `PacketRedirection.sendRedirectedPacket/sendRedirectedMessage`, `ImmPtlNetworking.TeleportPacket.handle` (twice, `ImmPtlNetworking.java:78,81`), and `MixinPlayerList_Misc` must route through `player.level().getServer()` (`ServerPlayer.level()` returns `ServerLevel`, `:1731`; `ServerLevel.getServer()`, `:1278`) — or, in the Fabric play receiver that invokes `TeleportPacket.handle`, `Context.server()` (F2).
8. **The `PortalSyncPacket` client spawn path has three renames:** `EntityType.create(Level)` → `create(Level, EntitySpawnReason)` (use `EntitySpawnReason.LOAD`, vanilla's own spawn-packet reason), `Entity.moveTo(x,y,z)` → `snapTo(x,y,z)`, and the `CompoundTag` Optional-getter rework (relevant to `DimIdSyncPacket` NBT map decode too).

---

## GONE (3 vanilla)

### G1. `PacketUtils.ensureRunningOnSameThread(Packet, T, BlockableEventLoop)` — the "packet re-queue rides the Minecraft event loop" contract
| | |
|---|---|
| **IP usage** | Behavioral dependency of the whole client redirection design: `PacketRedirectionClient` javadoc relies on inner vanilla packets re-submitting through `Minecraft`'s executor (`PacketRedirectionClient.java:79-87`); `MixinMinecraft_RedirectedPacket` intercepts `wrapRunnable` + forces inline execution via `scheduleExecutables()` to keep the re-submitted task dimension-correct and un-delayed (`MixinMinecraft_RedirectedPacket.java:23-59`); IP itself re-submits off-thread redirected packets via `minecraft.execute(...)` (`PacketRedirectionClient.java:70-76`). |
| **Verdict** | **GONE** — no `BlockableEventLoop` overload exists. 26.2 overloads: `ensureRunningOnSameThread(Packet, T, ServerLevel)` (`network/protocol/PacketUtils.java:17-19`) and `ensureRunningOnSameThread(Packet, T, PacketProcessor)` (`PacketUtils.java:21-26`), which calls `packetProcessor.scheduleIfPossible(listener, packet)` then throws `RunningOnDifferentThreadException`. |
| **26.2 mechanism** | `PacketProcessor` is a plain concurrent queue bound to a thread (`network/PacketProcessor.java:12-32`); drain calls `listener.shouldHandleMessage(packet)` then `packet.handle(listener)` (`PacketProcessor.java:47-62`). Client instance: `Minecraft.packetProcessor` built on `gameThread` (`client/Minecraft.java:369,730`), public getter `packetProcessor()` (`:2922`), drained in `runTick` under `"scheduledPacketProcessing"` **before** `runAllTasks()` (`Minecraft.java:1169-1172`). Server instance drained at `MinecraftServer.java:1009`. All ~60 `ClientPacketListener` handlers now call `ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor())` (e.g. `client/multiplayer/ClientPacketListener.java:492`). `Connection.channelRead0` still invokes `packet.handle(listener)` on the netty thread first (`network/Connection.java:146-170`), so the netty-pre-pass/main-pass double-invocation shape is preserved — only the re-queue *destination* changed. |
| **Migration note** | Two distinct consequences. **(1) Ordering:** on 1.21 both IP's `minecraft.execute` self-resubmit and vanilla's re-queue landed in the same event-loop queue, preserving packet arrival order. On 26.2 a naive port puts redirected packets in `pendingRunnables` (drained at `:1172`) while vanilla re-queued packets go to `packetProcessor` (drained first, `:1170`) → a vanilla packet arriving *after* a redirected one can be handled *before* it every frame. The order-faithful mechanism is to re-queue the **outer `ClientboundCustomPayloadPacket`** via `minecraft.packetProcessor().scheduleIfPossible(listener, packet)` — mirroring vanilla's own re-queue — with a same-thread guard in `MixinClientboundCustomPayloadPacket` (netty pass: schedule + cancel; main pass: `handleRedirectedPacket` inline + cancel). See §A. **(2) Hook coverage:** `wrapRunnable`/`scheduleExecutables` no longer see any vanilla packet re-queue; they still cover `Minecraft.execute` calls made *by handlers during* redirected processing, which is what they were for — keep them (see S15/S16 rows). |

### G2. `Minecraft.gui.getChat().addMessage(Component)` (client chat lines)
| | |
|---|---|
| **IP usage** | RPC failure red-chat: `ImplRemoteProcedureCall.clientTellFailure` → `Minecraft.getInstance().gui.getChat().addMessage(...)` (`ImplRemoteProcedureCall.java:408-413`); version-mismatch / missing-mod warnings `ImmPtlNetworkConfig.onClientJoin` (`ImmPtlNetworkConfig.java:279-302,313-317`). |
| **Verdict** | **GONE** — `Gui` has no `getChat()` (chat moved into `Hud`), and `ChatComponent.addMessage(Component)` is no longer public (only a private 4-arg `addMessage`, `client/gui/components/ChatComponent.java:255`). |
| **26.2 replacement** | Chat lives on the HUD: `Gui.hud` public field (`client/gui/Gui.java:72`) → `Hud.getChat()` (`client/gui/Hud.java:1264`) → **`ChatComponent.addClientSystemMessage(Component)`** (`ChatComponent.java:243`, public; `addServerSystemMessage` `:247`). Vanilla's routing layer is `Gui.chatListener()` (`Gui.java:294`) → `ChatListener.handleSystemMessage(Component, boolean remote)` (`client/multiplayer/chat/ChatListener.java:214-228`), which for `remote=false` calls `gui.hud.getChat().addClientSystemMessage(message)` (`:225`). |
| **Migration note** | For mod-generated local lines the 1:1 mapping is `minecraft.gui.hud.getChat().addClientSystemMessage(msg)` (bypasses narrator/blocklist, like the old `addMessage`). `ChatListener.handleSystemMessage(msg, false)` additionally narrates and respects blocking — a slight behavior difference; pick the direct `addClientSystemMessage` for zero deviation from IP's `addMessage` semantics. |

### G3. `ServerPlayer.server` (public field)
| | |
|---|---|
| **IP usage** | `PacketRedirection.sendRedirectedPacket` → `serverPlayNetworkHandler.player.server` (`PacketRedirection.java:122`); `sendRedirectedMessage` → `player.server` (`:181`); `ImmPtlNetworking.TeleportPacket.handle` → `PortalAPI.serverIntToDimKey(player.server, dimensionId)` (`ImmPtlNetworking.java:78`) and `ServerTeleportationManager.of(player.server)` (`:81`); `MixinPlayerList_Misc` → `DimIdSyncPacket.createPacket(player.server)` (`MixinPlayerList_Misc.java:29`). |
| **Verdict** | **GONE** as public surface — the field is now `private final MinecraftServer server` (`server/level/ServerPlayer.java:232`); no getter on `ServerPlayer`. |
| **26.2 replacement** | `player.level().getServer()` — `ServerPlayer.level()` covariantly returns `ServerLevel` (`ServerPlayer.java:1731`) and `ServerLevel.getServer()` is public non-null (`server/level/ServerLevel.java:1278`). In `MixinPlayerList_Misc`, `PlayerList.getServer()` also works (`server/players/PlayerList.java:695`). The redirection mixin already shadows `ServerCommonPacketListenerImpl.server` (still present, `protected final`, `server/network/ServerCommonPacketListenerImpl.java:39`) — inside listener-scoped code prefer that shadow. In `TeleportPacket.handle` no listener-scoped `server` shadow exists — use the Fabric receiver's `Context.server()` (F2) or `player.level().getServer()`. |
| **Migration note** | Mechanical. Five call sites in this slice (two in `PacketRedirection`, two in `ImmPtlNetworking.TeleportPacket.handle`, one in `MixinPlayerList_Misc`); grep the rest of the port for `\.server\b` on `ServerPlayer` when other slices arrive. |

---

## CHANGED (14 vanilla)

### C1. `ResourceLocation` → `Identifier` (cross-cutting rename)
| | |
|---|---|
| **IP usage** | `PacketRedirection.payloadId = McHelper.newResourceLocation("i:r")` (`PacketRedirection.java:47-48`); every payload `Type` id (`ImmPtlNetworking.java:50,98,146`, `ImmPtlNetworkConfig.java`, `MiscNetworking.java`, `ImplRemoteProcedureCall.java`); RPC `ResourceLocation` arg codec (`ImplRemoteProcedureCall.java:357-375`); `Helper.dimIdToKey` (`q_misc_util/Helper.java:508`). |
| **Verdict** | **CHANGED** — class renamed to `net.minecraft.resources.Identifier` (`resources/Identifier.java:18`). |
| **26.2 equivalent** | Factories: `Identifier.fromNamespaceAndPath(ns, path)` (`:40`), `Identifier.parse("ns:path")` (`:44`), `Identifier.withDefaultNamespace(path)` (`:48`); `Identifier.STREAM_CODEC` (`:20`). |
| **Migration note** | `"i:r"` becomes `Identifier.parse("i:r")` (or `fromNamespaceAndPath("i","r")`). Non-`minecraft` namespaces remain legal. Pure rename otherwise. |

### C2. `CustomPacketPayload.Type` id component type
| | |
|---|---|
| **IP usage** | `new CustomPacketPayload.Type<>(payloadId)` for all 8 payloads (`PacketRedirection.java:249-250`, `ImmPtlNetworking.java:50` etc.). |
| **Verdict** | **CHANGED** (type of the single ctor arg) — `record Type<T extends CustomPacketPayload>(Identifier id)` (`network/protocol/common/custom/CustomPacketPayload.java:56`). Interface shape otherwise identical: `type()` (`:14`), static `codec(StreamMemberEncoder, StreamDecoder)` (`:16-18`). |
| **26.2 equivalent** | Same construction with an `Identifier`. Convenience `CustomPacketPayload.createType(String)` exists but forces the `minecraft` namespace (`:20-22`) — not usable for `i:r`/`imm_ptl:*`. |
| **Migration note** | Follows C1 mechanically. |

### C3. `FriendlyByteBuf.readResourceLocation/writeResourceLocation`
| | |
|---|---|
| **IP usage** | RPC `ResourceLocation`/`ResourceKey` arg serialization (`ImplRemoteProcedureCall.java:357-375`, keys written as their location). |
| **Verdict** | **CHANGED** — renamed `readIdentifier()` (`network/FriendlyByteBuf.java:577`) / `writeIdentifier(Identifier)` (`:581`). |
| **26.2 equivalent** | Same UTF-based wire format (`:577-583`); `writeResourceKey` path uses `key.identifier()` (`:592`). |
| **Migration note** | Wire-compatible; rename only. |

### C4. `PacketSendListener` (send-callback parameter type)
| | |
|---|---|
| **IP usage** | Type appears in the redirection mixin's method descriptors and handler signature (`MixinServerGamePacketListenerImpl_Redirect.java:24,42-50`); IP never constructs one. |
| **Verdict** | **CHANGED** — the parameter type on every send path is now netty's `io.netty.channel.ChannelFutureListener`; `PacketSendListener` survives as a static utility producing them: `thenRun(Runnable)` (`network/PacketSendListener.java:13-20`), `exceptionallySend(Supplier<Packet<?>>)` (`:22-34`). |
| **26.2 equivalent** | `ServerCommonPacketListenerImpl.send(Packet<?>, @Nullable ChannelFutureListener)` (`server/network/ServerCommonPacketListenerImpl.java:160`); `Connection.send(Packet<?>, @Nullable ChannelFutureListener, boolean)` (`network/Connection.java:281`). |
| **Migration note** | Only affects descriptors (see C5) and the mixin handler's second parameter type. |

### C5. Mixin targets: `ServerCommonPacketListenerImpl.send(...)` + inner `Connection.send(...)`
| | |
|---|---|
| **IP usage** | `@ModifyVariable` on `send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V` HEAD (the blanket auto-wrap) and `@Inject` before `Connection.send(Packet;PacketSendListener;Z)V` (force-bundle diversion) (`MixinServerGamePacketListenerImpl_Redirect.java:23-60`); shadowed `protected MinecraftServer server` (`:20`). |
| **Verdict** | **CHANGED** — descriptors only. |
| **26.2 equivalent** | Outer method: `send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V` (`ServerCommonPacketListenerImpl.java:160-175`). One-arg `send(Packet)` still delegates to it with `null` (`:156-158`) — the two-arg inject still covers **all** sends. Inner INVOKE target: `Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V` (`Connection.java:281`). Shadow field unchanged: `protected final MinecraftServer server` (`:39`). |
| **Migration note** | The 26.2 body also gained `if (packet.isTerminal()) this.close()` before the connection send (`:161-163`) — the `@ModifyVariable` at HEAD still rewrites the packet before that check; a redirect-wrapped packet is a custom-payload packet and never terminal, and `createRedirectedMessage` passes bundles through re-bundled, so semantics hold. Keep the existing "class named ...ServerGamePacketListenerImpl_Redirect actually mixes into ServerCommonPacketListenerImpl" arrangement as-is. |

### C6. `ReentrantBlockableEventLoop(String)` ctor (mixin super-calls)
| | |
|---|---|
| **IP usage** | Dummy ctors of `MixinMinecraft_RedirectedPacket` (`:18-20`) and `MixinMinecraftServer_Misc` (`:29-32`) call `super(string)`. |
| **Verdict** | **CHANGED** — ctor is now `(String name, boolean propagatesCrashes)` (`util/thread/ReentrantBlockableEventLoop.java:6-8`; `BlockableEventLoop` ctor `:35-39`). |
| **26.2 equivalent** | `super(string, false)` (value irrelevant — mixin ctors never run). |
| **Migration note** | Mechanical; two files. |

### C7. `MinecraftServer.<init>` (arg-capturing @Inject)
| | |
|---|---|
| **IP usage** | `MixinMinecraftServer_Misc.onConstruct` captures the full 1.21 ctor arg list `(Thread, LevelStorageAccess, PackRepository, WorldStem, Proxy, DataFixer, Services, ChunkProgressListenerFactory)` (`MixinMinecraftServer_Misc.java:48-56`) to stash the `MiscGlobals.refMinecraftServer` WeakReference. |
| **Verdict** | **CHANGED** — 26.2 ctor is `(Thread, LevelStorageSource.LevelStorageAccess, PackRepository, WorldStem, Optional<GameRules>, Proxy, DataFixer, Services, LevelLoadListener, boolean, NotificationManager)` (`server/MinecraftServer.java:311-323`). `ChunkProgressListenerFactory` is gone from the ctor (replaced by `LevelLoadListener`). |
| **26.2 equivalent** | Same `@Inject(method = "<init>", at = @At("RETURN"))`; since the handler only uses `this`, drop the arg capture entirely (no-arg handler) rather than tracking the new list. |
| **Migration note** | Zero-deviation-safe: IP's handler body ignores every captured arg. |

### C8. `MinecraftServer.createLevels(ChunkProgressListener)` (mixin target)
| | |
|---|---|
| **IP usage** | `@Inject(method = "createLevels", at = @At("RETURN"))` with `ChunkProgressListener listener` arg → `DimensionIntId.onServerStarted` (`MixinMinecraftServer_Misc.java:58-61`). |
| **Verdict** | **CHANGED** — now **no-arg** `protected void createLevels()` (`server/MinecraftServer.java:421`), called from server bootstrap (`:402`). |
| **26.2 equivalent** | Same inject, handler signature `(CallbackInfo ci)` only. |
| **Migration note** | Injection timing (all `ServerLevel`s constructed, before first tick) is preserved — the dim-int-id map init point carries over 1:1. |

### C9. `RegistryAccess.registryOrThrow(Registries.DIMENSION_TYPE)`
| | |
|---|---|
| **IP usage** | `MiscNetworking.createFromServer` — dim → dimension-type id map (`MiscNetworking.java:64-70`). |
| **Verdict** | **CHANGED** — renamed `lookupOrThrow` (`core/RegistryAccess.java:21`, default method returning `Registry<E>`). |
| **26.2 equivalent** | `server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)`; `Registries.DIMENSION_TYPE` unchanged (`core/registries/Registries.java:274`). |
| **Migration note** | `Registry.getKey(T)` for the reverse lookup is unchanged (returns `Identifier` now, `core/Registry.java:59`). |

### C10. `ResourceKey.location()` / `ResourceKey.create(...)` param
| | |
|---|---|
| **IP usage** | RPC writes keys as their location (`ImplRemoteProcedureCall.java:357-375`); `BuiltinDimensionTypes.OVERWORLD.location()` fallback (`MiscNetworking.java:64-70`); `Helper.dimIdToKey` builds keys via `ResourceKey.create(Registries.DIMENSION, ...)` (`Helper.java:508`). |
| **Verdict** | **CHANGED** — `location()` renamed `identifier()` (`resources/ResourceKey.java:64`); `create` now takes an `Identifier` (`ResourceKey.java:26`). |
| **26.2 equivalent** | `ResourceKey.create(Registries.DIMENSION, Identifier.parse(str))`; `key.identifier()`. `Registries.DIMENSION` `:308`, `Registries.BIOME` `:260` (RPC `ResourceKey<Biome>` deserialization), `Registries.ENTITY_TYPE` `:189` — all present in `core/registries/Registries.java`. |
| **Migration note** | Follows C1. The dim-id NBT wire format (`"intids"` string keys) is untouched — only the parse/format calls rename. |

### C11. `EntityType.create(Level)` (PortalSyncPacket client spawn)
| | |
|---|---|
| **IP usage** | `entityType.create(world)` when the portal isn't yet present client-side (`ImmPtlNetworking.java:206`). |
| **Verdict** | **CHANGED** — now `public @Nullable T create(Level level, EntitySpawnReason reason)` (`world/entity/EntityType.java:298`; also an `EntitySpawnRequest` overload `:302`). |
| **26.2 equivalent** | `entityType.create(world, EntitySpawnReason.LOAD)` — **`LOAD` is exactly what vanilla's own add-entity-packet path uses**: `ClientPacketListener` creates packet-spawned entities with `type.create(this.level, EntitySpawnReason.LOAD)` (`client/multiplayer/ClientPacketListener.java:601`). `EntitySpawnReason` at `world/entity/EntitySpawnReason.java:3`. |
| **Migration note** | Since `PortalSyncPacket` is IP's replacement for `ClientboundAddEntityPacket`, mirroring the vanilla spawn-packet reason is the fidelity-correct choice. |

### C12. `Entity.moveTo(x, y, z)` (PortalSyncPacket client spawn)
| | |
|---|---|
| **IP usage** | `entity.moveTo(x, y, z)` after `syncPacketPositionCodec` (`ImmPtlNetworking.java:210-213`). |
| **Verdict** | **CHANGED** — renamed `snapTo`: `public void snapTo(double x, double y, double z)` (`world/entity/Entity.java:1782`; overloads `:1778-1794`). |
| **26.2 equivalent** | `entity.snapTo(x, y, z)` — same "set position + rotation-preserving" semantics (delegates to the 5-arg form with current rot, `:1783`). |
| **Migration note** | Rename only. `syncPacketPositionCodec(x,y,z)` is unchanged (`Entity.java:355`). |

### C13. `CompoundTag` accessor rework (NBT-heavy payload handlers)
| | |
|---|---|
| **IP usage** | `DimIdSyncPacket.handle` iterates `dimIntIdTag`/`dimTypeTag` with `getCompound`/`getAllKeys`/`getInt`/`getString` (`MiscNetworking.java:99-126`); `DimIntIdMap.fromTag/toTag` (`DimIntIdMap.java:112-143`); RPC `CompoundTag` arg type; `GlobalPortalSyncPacket`/`PortalSyncPacket` carry NBT opaquely. |
| **Verdict** | **CHANGED** — getters are Optional-returning or `*Or`-defaulted: `getAllKeys()` → `keySet()` (`nbt/CompoundTag.java:193`); `getInt(String)` → `Optional<Integer>` (`:299`) / `getIntOr(String,int)` (`:303`); `getString` → `Optional<String>` (`:331`) / `getStringOr` (`:335`); `getCompound` → `Optional<CompoundTag>` (`:351`) / `getCompoundOrEmpty` (`:355`). Unchanged: `contains(String)` (`:275`), `put` (`:223`), `putString` (`:251`). |
| **26.2 equivalent** | For map iteration, `getCompoundOrEmpty`/`getIntOr`/`getStringOr` reproduce the old lenient defaults 1:1. `IntTag.valueOf` unchanged (`nbt/IntTag.java:44`). `FriendlyByteBuf.readNbt()` still returns `@Nullable CompoundTag` (`network/FriendlyByteBuf.java:534`), `writeNbt(@Nullable Tag)` (`:517`). `NbtUtils` still exists (`nbt/NbtUtils.java:42`). |
| **Migration note** | Choose the `*Or` variants to match 1.21's silent-default semantics exactly (the old `getInt` returned 0 on missing key; `getIntOr(key, 0)` is the 1:1 port). Do not switch to throwing `Optional.orElseThrow` — that would change failure behavior on malformed sync data. |

### C14. authlib `GameProfile.getName()/getId()`
| | |
|---|---|
| **IP usage** | Version-handshake logging (`ImmPtlNetworkConfig.java:157-194,234-240` — `gameProfile.getName(), gameProfile.getId()`). |
| **Verdict** | **CHANGED** — the bundled authlib is record-style: 26.2 vanilla calls `this.gameProfile.name(), this.gameProfile.id()` (`server/network/ServerConfigurationPacketListenerImpl.java:69`). |
| **26.2 equivalent** | `.name()` / `.id()`. New wrapper `NameAndId(gameProfile)` also exists where vanilla needs it (`ServerConfigurationPacketListenerImpl.java:104`). |
| **Migration note** | Rename only (log strings). |

---

## SAME (30 vanilla)

### S1. `StreamCodec.of(encoder, decoder)`
All 8 payload CODECs (`PacketRedirection.java:252-255` etc.). 26.2: `static <B, V> StreamCodec<B, V> of(StreamEncoder<B, V>, StreamDecoder<B, V>)` (`network/codec/StreamCodec.java:21`); `ofMember` also present (`:35`). **SAME.**

### S2. `ByteBufCodecs.registry(Registries.ENTITY_TYPE)`
`PortalSyncPacket` entity-type field (`ImmPtlNetworking.java:156,167`). 26.2: `static <T> StreamCodec<RegistryFriendlyByteBuf, T> registry(ResourceKey<? extends Registry<T>>)` (`network/codec/ByteBufCodecs.java:582`), id-mapped via `input.registryAccess().lookupOrThrow(...)` (`:562-570`). **SAME.**

### S3. `FriendlyByteBuf` core read/writes
varint `readVarInt`/`writeVarInt` (`network/FriendlyByteBuf.java:481,507`); `readUUID`/`writeUUID` (`:499,489`); `readNbt()`/`writeNbt` (`:534,517`); `readUtf`/`writeUtf` (`:560,568`); `readBlockPos`/`writeBlockPos` (`:381,389`); `readByteArray`/`writeByteArray` (`:272,280`). `readDouble`/`writeDouble` come from the netty `ByteBuf` supertype (`public class FriendlyByteBuf extends ByteBuf`, `:71`). **SAME** (see C3 for the identifier pair).

### S4. `RegistryFriendlyByteBuf`
Registry-aware payloads (`PortalSyncPacket`, RPC, redirection `Payload`). 26.2: `public class RegistryFriendlyByteBuf extends FriendlyByteBuf` (`network/RegistryFriendlyByteBuf.java:7`), ctor `(ByteBuf, RegistryAccess)` (`:10`), `registryAccess()` (`:15`), static `decorator(RegistryAccess)` (`:19`). **SAME.**

### S5. `GameProtocols.CLIENTBOUND_TEMPLATE.bind(...)` → `ProtocolInfo.codec()` — **the redirection wire format survives**
IP: `GameProtocols.CLIENTBOUND_TEMPLATE.bind(argBuf -> (RegistryFriendlyByteBuf) argBuf)` stored as `ProtocolInfo<ClientGamePacketListener>` (`PacketRedirection.java:234-241`), `.codec().encode/decode` (`:258-275`). 26.2: `public static final SimpleUnboundProtocol<ClientGamePacketListener, RegistryFriendlyByteBuf> CLIENTBOUND_TEMPLATE` (`network/protocol/game/GameProtocols.java:131`); `SimpleUnboundProtocol.bind(Function<ByteBuf, B>) → ProtocolInfo<T>` (`network/protocol/SimpleUnboundProtocol.java:8-9`); `ProtocolInfo.codec() → StreamCodec<ByteBuf, Packet<? super T>>` (`network/ProtocolInfo.java:17`). The identity-cast bind is valid for the same reason as on 1.21: vanilla itself binds this template with a `RegistryFriendlyByteBuf` producer (`ServerConfigurationPacketListenerImpl.java:163`, `client/multiplayer/ClientConfigurationPacketListenerImpl.java:163` — `RegistryFriendlyByteBuf.decorator(registryAccess)`), so the buf reaching the payload codec already is one. **SAME at the call site** (the unbound-template *type name* is `SimpleUnboundProtocol` — IP never names it, so nothing to change; a two-context `UnboundProtocol.bind(wrapper, context)` variant also exists, `network/protocol/UnboundProtocol.java:8-9`, not needed). Full clientbound-game codec includes packet id + body via `ProtocolInfoBuilder.clientboundProtocol` (`GameProtocols.java:131-133`).

### S6. `ClientboundCustomPayloadPacket` (ctor, `payload()`, `handle` mixin target)
IP constructs it (`PacketRedirection.java:172`), reads `payload()` (`:186-187`), and HEAD-injects `handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V` (`MixinClientboundCustomPayloadPacket.java:24-28`, shadowing `payload`). 26.2: `public record ClientboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ClientCommonPacketListener>` (`network/protocol/common/ClientboundCustomPayloadPacket.java:15`) — public ctor, `payload()` accessor, and `public void handle(ClientCommonPacketListener)` (`:32-34`) all match; the record-component field `payload` is shadowable. `GAMEPLAY_STREAM_CODEC` (RegistryFriendlyByteBuf) `:17`, 1 MiB cap `:16`. **SAME** — but read §A before porting the mixin: the *threading around* `handle` changed (PacketProcessor re-queue, ordering), and 26.2 short-circuits `DiscardedPayload` before the thread hop (`client/multiplayer/ClientCommonPacketListenerImpl.java:159-170`).

### S7. Bundle machinery: `ClientboundBundlePacket`, `BundlePacket`, `BundleDelimiterPacket`
IP re-bundles wrapped sub-packets (`PacketRedirection.java:146-159,219-225`) and asserts against raw delimiters (`:145`). 26.2: `ClientboundBundlePacket(Iterable<Packet<? super ClientGamePacketListener>>)` (`network/protocol/game/ClientboundBundlePacket.java:8`); `BundlePacket.subPackets()` (`network/protocol/BundlePacket.java:12`); `BundleDelimiterPacket` abstract with pipeline-only `handle` (`network/protocol/BundleDelimiterPacket.java:5-9`); concrete `ClientboundBundleDelimiterPacket` registered via `withBundlePacket(...)` (`GameProtocols.java:133`). **SAME** (IP's `List`-cast into the `Iterable` ctor compiles as before).

### S8. `Packet` / `PacketListener`
`public interface Packet<T extends PacketListener>` (`network/protocol/Packet.java:9`) — now also carries `isTerminal()`/`isSkippable()` used by crash reporting (`PacketUtils.java:43-44`); `public interface PacketListener` (`network/PacketListener.java:11`) gained `shouldHandleMessage(Packet)` (checked at `PacketProcessor.java:49`). **SAME** for IP's usage.

### S9. Listener interfaces + `ClientPacketListener` members
`ClientGamePacketListener` (`network/protocol/game/ClientGamePacketListener.java:7`), `ClientCommonPacketListener` (`network/protocol/common/ClientCommonPacketListener.java:5`), `ServerCommonPacketListener` (`network/protocol/common/ServerCommonPacketListener.java:5`). `ClientPacketListener` (`client/multiplayer/ClientPacketListener.java:343`): `handleAddEntity(ClientboundAddEntityPacket)` (`:566`), `handleBundlePacket(ClientboundBundlePacket)` (`:2441`), private field `Set<ResourceKey<Level>> levels` (`:403`) — the `IEClientPacketListener_Misc` setter accessor still targets it (not final → plain `@Accessor` works); note 26.2 added a public **getter** `levels()` (`:2624`), no setter. **SAME.**

### S10. `ServerGamePacketListenerImpl.send(Packet)` / `.player`
`send(Packet<?>)` inherited from `ServerCommonPacketListenerImpl` (`ServerCommonPacketListenerImpl.java:156-158`); `public ServerPlayer player` (`server/network/ServerGamePacketListenerImpl.java:239`). **SAME.**

### S11. `ConfigurationTask` (+`.Type`, `start`)
`public interface ConfigurationTask { void start(Consumer<Packet<?>> connection); ... record Type(String id) }` (`server/network/ConfigurationTask.java:6-20`) — identical; a default `tick()` was added (`:9-11`), harmless. **SAME.**

### S12. `ServerConfigurationPacketListenerImpl.disconnect(Component)` / `gameProfile` field
`disconnect(Component)` inherited (`ServerCommonPacketListenerImpl.java:177-179`). `private final GameProfile gameProfile` (`ServerConfigurationPacketListenerImpl.java:49`) — the `IEServerConfigurationPacketListenerImpl` `@Accessor("gameProfile")` still lands; a `protected GameProfile playerProfile()` override also exists now (`:62-65`) if an accessor-free route is preferred. **SAME.** (`completeTask`/`addTask` are Fabric interface injection — see F8.)

### S13. `ClientboundAddEntityPacket` (doc analog)
`public class ClientboundAddEntityPacket implements Packet<ClientGamePacketListener>` (`network/protocol/game/ClientboundAddEntityPacket.java:17`). **SAME** (reference only; `PortalSyncPacket` remains its replacement).

### S14. `Minecraft.getInstance()` / `.execute` / `.isSameThread()` / `wrapRunnable` (mixin target)
`getInstance()` (`client/Minecraft.java:2517`); `execute(Runnable)` from `BlockableEventLoop` (`util/thread/BlockableEventLoop.java:98-105` — still routes through `wrapRunnable`, `:99`); `isSameThread()` (`:43-45`); `Minecraft.wrapRunnable(Runnable)` override exists and still returns identity (`Minecraft.java:2668-2670`) — the `MixinMinecraft_RedirectedPacket.onCreateTask` HEAD inject target survives verbatim. **SAME** — but see G1/§A: `execute` no longer carries vanilla packet re-queues, only explicit task submissions.

### S15. `scheduleExecutables()` / `runningTask()` (mixin override targets)
`BlockableEventLoop.scheduleExecutables()` (`BlockableEventLoop.java:49-51`); `ReentrantBlockableEventLoop.scheduleExecutables()` override + `runningTask()` (`ReentrantBlockableEventLoop.java:11-17`). IP's `@Override public boolean scheduleExecutables()` widening (`MixinMinecraft_RedirectedPacket.java:47-59`) still applies; `Minecraft` itself does not override it (verified by grep — only `getRunningThread` `:2663`, `wrapRunnable` `:2668`, `shouldRun` `:2673`). **SAME** (ctor change is C6). Its purpose narrows per G1: it now guarantees inline execution only for `Minecraft.execute`-submitted tasks during redirected handling, which is exactly the remaining need.

### S16. `BlockableEventLoop` / `MinecraftServer extends ReentrantBlockableEventLoop`
`public abstract class BlockableEventLoop<R extends Runnable> implements Executor, TaskScheduler<R>...` (`BlockableEventLoop.java:26`); `public abstract class MinecraftServer extends ReentrantBlockableEventLoop<TickTask>...` (`server/MinecraftServer.java:192`). **SAME.**

### S17. `PlayerList.placeNewPlayer` + `ClientboundChangeDifficultyPacket.<init>` INVOKE point
`public void placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` (`server/players/PlayerList.java:142`); inside it, `new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked())` (`:185`), after the login packet (`:170` region) — the dim-id sync still lands inside the join burst, before any chunk/portal payloads. Packet is now a record but the ctor descriptor is unchanged: `(Lnet/minecraft/world/Difficulty;Z)V` (`network/protocol/game/ClientboundChangeDifficultyPacket.java:10`; `world/Difficulty` confirmed present). **SAME** as mixin target (`player.server` inside the handler body → G3).

### S18. `MinecraftServer` getters
`levelKeys()` (`server/MinecraftServer.java:1193`), `getAllLevels()` (`:1197`), `isDedicatedServer()` (`:1340`), `getPlayerList()` (`:1383`) + `PlayerList.getPlayers()` (`server/players/PlayerList.java:817`), `registryAccess()` (`:2005`). **SAME.**

### S19. `ServerLevel.dimension()` / `.dimensionType()` / `.getServer()`
`Level.dimension()` (`world/level/Level.java:960`), `Level.dimensionType()` (`:952`), `ServerLevel.getServer()` (`server/level/ServerLevel.java:1278`). **SAME.**

### S20. `Level.OVERWORLD/NETHER/END`
`world/level/Level.java:95-97` (built with `Identifier.withDefaultNamespace`). **SAME.**

### S21. `Registry.getKey` / `BuiltinDimensionTypes.OVERWORLD`
`@Nullable Identifier getKey(T)` (`core/Registry.java:59`; impl `core/MappedRegistry.java:124`); `BuiltinDimensionTypes.OVERWORLD` (`world/level/dimension/BuiltinDimensionTypes.java:8`). **SAME** (`.location()` on the key → C10).

### S22. `ServerPlayer.connection` / `.sendSystemMessage` / `.getRemovalReason`
`public ServerGamePacketListenerImpl connection` (`server/level/ServerPlayer.java:231`); `sendSystemMessage(Component)` (`:1783`); `Entity.getRemovalReason()` (`world/entity/Entity.java:3907`). **SAME** (`.server` → G3).

### S23. Entity id/uuid/position members (client spawn path)
`setId(int)` (`world/entity/Entity.java:389`), `setUUID(UUID)` (`:3240`), `syncPacketPositionCodec(double,double,double)` (`:355`), `getId()` (`:381`), `getUUID()` (`:3246`), `getType()` (`:363`). **SAME** (`moveTo` → C12, `EntityType.create` → C11).

### S24. `ClientLevel.getEntity(int)` / `.addEntity(Entity)`
`client/multiplayer/ClientLevel.java:551` / `:529`. **SAME.**

### S25. `IntTag.valueOf` / `NbtUtils`
`nbt/IntTag.java:44`; `nbt/NbtUtils.java:42`. **SAME.**

### S26. `ComponentSerialization.TRUSTED_STREAM_CODEC`
`public static final StreamCodec<RegistryFriendlyByteBuf, Component> TRUSTED_STREAM_CODEC` (`network/chat/ComponentSerialization.java:40`); context-free variant `:44`. **SAME** (RPC `Component` arg codec ports 1:1).

### S27. RPC value codecs: `byNameCodec` / `BlockState.CODEC` / `ItemStack.CODEC`
`Registry.byNameCodec()` (`core/Registry.java:29`); `BuiltInRegistries.BLOCK` (`core/registries/BuiltInRegistries.java:184`) / `.ITEM` (`:189`); `BlockState.CODEC` (`world/level/block/state/BlockState.java:9`); `ItemStack.CODEC` (`world/item/ItemStack.java:121`). **SAME** as codec handles — but the RPC serializes these through **JSON strings** (`ImplRemoteProcedureCall.java:339-345,377-383`); the codecs' JSON *shape* may have drifted since 1.21 (ItemStack components churn). That is a wire-compat concern only between mismatched mod versions, which the version handshake already gates; within one version both ends use the same codec. No API change.

### S28. `Component.literal/translatable` / `MutableComponent` / `ChatFormatting`
`network/chat/Component.java:135,139,143`; `network/chat/MutableComponent.java:11`; `ChatFormatting` (`net/minecraft/ChatFormatting.java:7`). **SAME** (the chat *output* path → G2).

### S29. `Vec3` / `BlockPos` / `JsonOps.INSTANCE`
`world/phys/Vec3.java:18`; `core/BlockPos.java:32`; `com.mojang.serialization.JsonOps` still the DFU type vanilla itself uses (`network/FriendlyByteBuf.java:11,104-109`). **SAME.**

### S30. `MixinMinecraftServer_Misc` shadowed members
`storageSource` — exists, now `protected final` (`server/MinecraftServer.java:217`; IP declares the shadow `public` — align the shadow to `protected` when porting), `executor` (`:279`), `waitUntilNextTick` (used `:562,669`), `isDedicatedServer()` (`:1340`). **SAME** (visibility nit only).

**New 26.2 surface this slice will need (no 1.21 counterpart):** `Minecraft.packetProcessor()` (`client/Minecraft.java:2922`) and `PacketProcessor.scheduleIfPossible/isSameThread` (`network/PacketProcessor.java:22-32`) — see §A.

---

## FABRIC-API / external (10 — route through the mod's loader abstraction)

The mod is multiloader (`common`/`fabric`/`neoforge`); the existing routing point is `PlatformHelper` (common) implemented by `fabric/src/main/java/com/warwa/seamlessportals/fabric/network/FabricPlatformHelper.java` (payload registration + send + receiver registration already live there and compile against the 26.2 Fabric API). Every row below must go through that seam (or a new one for events/config phase); NeoForge needs the parallel impl.

### F1. `PayloadTypeRegistry.playC2S()/playS2C()/configurationC2S()/configurationS2C()`
**FABRIC-API, CHANGED (renamed).** v6: `serverboundPlay()` (`PayloadTypeRegistry.java:103`), `clientboundPlay()` (`:110`), `serverboundConfiguration()` (`:89`), `clientboundConfiguration()` (`:96`). `.register(TYPE, CODEC)` unchanged. Working proof: `FabricPlatformHelper.java:31-109` uses `clientboundPlay()`/`serverboundPlay()`.

### F2. `ServerPlayNetworking.registerGlobalReceiver` / `createS2CPacket`
**FABRIC-API.** `registerGlobalReceiver` exists; play handlers still run **on the server thread** (`ServerPlayNetworking.java:49,327`); `Context` has `server()`/`player()`/`responseSender()` (`:348-362`). **`createS2CPacket` is GONE in v6** — replace with `new ClientboundCustomPayloadPacket(payload)` (public record ctor, `ClientboundCustomPayloadPacket.java:15`) where IP builds a `Packet<?>` (`Portal.createSyncPacket`, `MiscNetworking.createPacket`, `ImplRemoteProcedureCall.createS2CPacket`), or `PacketSender.createPacket(CustomPacketPayload)` (`PacketSender.java:38`). Direct ctor is the loader-neutral choice for the multiloader seam.

### F3. `ClientPlayNetworking.registerGlobalReceiver` / `createC2SPacket`
**FABRIC-API.** Receiver exists; handlers run **on the render thread** (`client/networking/v1/ClientPlayNetworking.java:263`). **`createC2SPacket` GONE** — `new ServerboundCustomPayloadPacket(payload)` (public record ctor, `network/protocol/common/ServerboundCustomPayloadPacket.java:13`) for `TeleportPacket`/`tellServerToInvoke` sends via `player.connection.send(...)`.

### F4. `ServerConfigurationNetworking` (`createS2CPacket`/`canSend`/`registerGlobalReceiver`/`Context.networkHandler()`)
**FABRIC-API, partly CHANGED.** `registerGlobalReceiver(Type, ConfigurationPacketHandler)` (`ServerConfigurationNetworking.java:69`); `canSend(listener, Type)` (`:176`); handlers still on **netty's event loops** (`:261`). `Context.networkHandler()` → **`packetListener()`** (`:290`, returns `ServerConfigurationPacketListenerImpl`); `server()` `:285`, `responseSender()` `:295`. `createS2CPacket` — use the vanilla ctor as in F2 (`ImmPtlConfigurationTask.start` sends via `Consumer<Packet<?>>`).

### F5. `ClientConfigurationNetworking.registerGlobalReceiver` (+ `Context.responseSender()`)
**FABRIC-API.** Exists; `Context` has `client()` (`client/networking/v1/ClientConfigurationNetworking.java:281`) and `responseSender()` (`:291`); handlers on **netty's event loops** (`:258`) — the 1.21 thread contract carries over, so `S2CConfigStartPacket.handle`'s static-field-write + reply pattern stays legal as-is.

### F6. `ServerConfigurationConnectionEvents.CONFIGURE`
**FABRIC-API.** Exists in v6 (`ServerConfigurationConnectionEvents.java:59`; plus `BEFORE_CONFIGURE` `:36`). The `handler.addTask(...)` call inside it is F8.

### F7. `ClientLoginConnectionEvents.INIT` / `ClientPlayConnectionEvents.JOIN`
**FABRIC-API.** Both exist in v6 (`ClientLoginConnectionEvents.java:37`; `ClientPlayConnectionEvents.java:48`). Used for per-connection `serverVersion` reset and the join-time chat warnings.

### F8. `ServerConfigurationPacketListenerImpl.addTask(...)` / `.completeTask(Type)` (interface injection)
**FABRIC-API.** Still provided by v6 interface injection: `FabricServerConfigurationPacketListenerImpl.addTask(ConfigurationTask)` (`FabricServerConfigurationPacketListenerImpl.java:40`) and `completeTask(ConfigurationTask.Type)` (`:50`). The vanilla method underneath is `private finishCurrentTask(ConfigurationTask.Type)` (`ServerConfigurationPacketListenerImpl.java:225`) — on NeoForge (or if dropping the Fabric injection) an accessor/invoker on `finishCurrentTask` + direct `configurationTasks.add` is the raw-vanilla equivalent.

### F9. `FabricLoader.getModContainer("iportal")` version lookup (`O_O.getImmPtlVersion`)
**FABRIC-API (loader).** Loader-specific; the multiloader port must supply the mod-version string through its platform abstraction (NeoForge: `ModContainer`/`ModList`). Only feeds `ImmPtlNetworkConfig.immPtlVersion`.

### F10. DimLib `DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` (+ early-phase ordering)
**EXTERNAL-DEP (DimLib), UNKNOWN-NEEDS-DESIGN.** `DimensionIntId.init` registers on DimLib's dynamic-dimension update event with a custom `iportal:early_phase` ordered before default (`DimensionIntId.java:31-44`) so int-ids update before global-portal storage reacts. DimLib is a separate qouteall mod not in scope of this port; the target mod has no dynamic-dimension system today. Decision for the port lead: (a) port/stub the event (fire never — vanilla dims only) while keeping `onServerDimensionChanged` intact for fidelity, or (b) bring DimLib's dimension API along. The int-id map itself (OW=0, nether=-1, end=1, monotonic ids) works without the event as long as dimensions are static.

---

## §A. Threading appendix — porting the redirection receive path onto `PacketProcessor`

**26.2 client packet lifecycle (verified):** netty thread `Connection.channelRead0` → `packet.handle(listener)` (`network/Connection.java:146-170`) → handler's `PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())` schedules `(listener, packet)` and throws (`PacketUtils.java:21-26`) → game thread drains `packetProcessor.processQueuedPackets()` in `runTick` (`Minecraft.java:1170`) → `packet.handle(listener)` runs again, this time passing the same-thread check. `runAllTasks()` (the `Minecraft.execute` queue) drains **after** the packet queue (`:1172`).

**What this means for `MixinClientboundCustomPayloadPacket` + `PacketRedirectionClient`:**
1. The HEAD inject into `ClientboundCustomPayloadPacket.handle` fires on the **netty pass first**, exactly as on 1.21. With IP's unconditional `ci.cancel()`, vanilla's own re-queue (`ClientCommonPacketListenerImpl.handleCustomPayload`'s `ensureRunningOnSameThread`, `ClientCommonPacketListenerImpl.java:159-162`) never runs for redirected payloads — so there is **no vanilla-driven double delivery** for this packet *when the mixin cancels on the netty pass*. The double-delivery hazard recorded in project memory applies to handler mixins that do NOT cancel; this one cancels.
2. **But** the packet must then reach the game thread in the right order. Porting IP's `minecraft.execute(self)` re-submit verbatim (`PacketRedirectionClient.java:70-76`) puts redirected packets in the queue drained at `:1172`, while every vanilla packet that arrived after them is drained at `:1170` — a per-frame reordering window that 1.21 did not have (both queues were one). Redirected chunk/entity packets racing non-redirected respawn/login/flag packets is precisely the class of bug this project has already paid for (respawn-mislabel, walking-limbo). **Order-faithful port:** in the mixin, replicate vanilla's own hop — netty pass: `minecraft.packetProcessor().scheduleIfPossible(listener, (Packet) outerPacket); ci.cancel();` — game-thread pass (the scheduled `handle` re-invocation hits the same HEAD inject with `minecraft.isSameThread()` true): call `Payload.handle(...)`/`handleRedirectedPacket` inline, then `ci.cancel()`. `handleRedirectedPacket`'s own `isSameThread` branch (`PacketRedirectionClient.java:45-77`) then never needs its `minecraft.execute` fallback for the outer hop. Note `PacketProcessor.scheduleIfPossible` throws `RejectedExecutionException` after close (`PacketProcessor.java:27-29`) and the drain checks `listener.shouldHandleMessage(packet)` (`:49`) — both are vanilla's own disconnect semantics, i.e. *more* faithful than a raw `minecraft.execute`.
3. `MixinMinecraft_RedirectedPacket` keeps both hooks with narrowed scope: `wrapRunnable` still wraps `Minecraft.execute` tasks submitted **during** redirected handling (mod code and any vanilla handler that defers work via `execute`); the `scheduleExecutables()` override still forces inline execution for such tasks while a redirected message is processing. What neither hook covers anymore — nested vanilla `ensureRunningOnSameThread` re-queues — cannot occur on this path, because the inner `packet.handle(handler)` is only ever invoked once the processor's thread check already passes.
4. Server side is unchanged in shape: C2S handlers go through the server `PacketProcessor` (`MinecraftServer.java:1009`) / Fabric play receivers stay on the server thread (F2), and IP's off-thread detector in `withForceRedirectAndGet` (`PacketRedirection.java:76-81`) ports as-is (the `IEWorld.portal_getThread` duck is another slice).

**Also carried from the inventory's port-blocking notes:** the `withForceBundle` facility (`PacketRedirection.java:190-227`) has no callers but is public API — port it; its interception point is the C5 `Connection.send(Packet;ChannelFutureListener;Z)V` inject. The deprecated `old_handleRedirectedPacket` (`PacketRedirectionClient.java:88-115`) and `DimensionIdRecord` Polymer shim (`DimensionIdRecord.java:12-18`) are dead code — fidelity call for the port lead, nothing in 26.2 blocks them.
