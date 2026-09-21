package com.warwa.seamlessportals.forge.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ForgePlatformHelper implements PlatformHelper {

    // ------------------------------------------------------------------
    // 26.3 FORGE PORT — the MinecraftForge twin of NeoForgePlatformHelper
    // (entity-portal migration, S0 loader seams; seam inventory:
    // migration/port-notes/S00-seam-inventory.md).
    //
    // FORGE 26.3 ("F-NET"): this binding uses NO Forge network channel. Common code builds
    // `new ClientboundCustomPayloadPacket(payload)` / `new ServerboundCustomPayloadPacket(payload)` itself (8 sites) and
    // INSPECTS the payload object on the way in (PacketRedirection.isRedirectPacket; MixinClientboundCustomPayloadPacket's
    // `payload instanceof PacketRedirection.Payload` at the HEAD of handle — the order-faithful redirect), so the mod's
    // payloads have to travel AS THEMSELVES, the way Fabric API and NeoForge make them. Forge's channel layer cannot do
    // that: both custom-payload packet codecs use ForgeHooks.getCustomPayloadCodec as their FALLBACK provider, whose
    // encoder is `lambda$getCustomPayloadCodec$0(ForgePayload, FriendlyByteBuf)` (ClassCastException for any other
    // payload object) and whose decoder yields a ForgePayload, never the mod's record. Instead:
    //   * the registries below are LIVE — read at encode/decode/receive time, so there is no pending/drain step for
    //     payloads (the entity-type/renderer queues still drain in their registry/client events);
    //   * MixinCustomPacketPayloadCodecForge makes vanilla's dispatch codec (CustomPacketPayload$1.findCodec — the ONE
    //     anonymous class behind ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC / CONFIG_STREAM_CODEC and
    //     ServerboundCustomPayloadPacket.STREAM_CODEC) resolve the mod's ids through findCodec(Identifier) here;
    //   * the three *_PayloadDispatchForge mixins hand received payloads to dispatchClientbound / dispatchServerbound at
    //     the HEAD of the listeners' handleCustomPayload, before Forge's own ForgeHooks.onCustomPayload.
    // ------------------------------------------------------------------

    // FORGE 26.3: LIVE codec registries, id -> codec, one per phase x direction (they replace NeoForge's
    // PENDING_CLIENTBOUND / PENDING_SERVERBOUND maps and the registrar they were drained into). IP's and the mod's
    // payload ids are phase- and direction-unique; findCodec relies on that, and putCodec enforces it.
    private static final Map<Identifier, StreamCodec<? super RegistryFriendlyByteBuf, ?>>
        PLAY_CLIENTBOUND = new ConcurrentHashMap<>();
    private static final Map<Identifier, StreamCodec<? super RegistryFriendlyByteBuf, ?>>
        PLAY_SERVERBOUND = new ConcurrentHashMap<>();
    private static final Map<Identifier, StreamCodec<? super FriendlyByteBuf, ?>>
        CONFIG_CLIENTBOUND = new ConcurrentHashMap<>();
    private static final Map<Identifier, StreamCodec<? super FriendlyByteBuf, ?>>
        CONFIG_SERVERBOUND = new ConcurrentHashMap<>();
    private static final List<Map<Identifier, ? extends StreamCodec<?, ?>>> ALL_CODEC_REGISTRIES =
        List.of(PLAY_CLIENTBOUND, PLAY_SERVERBOUND, CONFIG_CLIENTBOUND, CONFIG_SERVERBOUND);

    private static final Map<CustomPacketPayload.Type<?>, ServerPayloadHandler<?>>
        SERVER_HANDLERS = new ConcurrentHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, ClientPayloadHandler<?>>
        CLIENT_HANDLERS = new ConcurrentHashMap<>();
    private static final List<Consumer<BiConsumer<Identifier, EntityType<?>>>>
        PENDING_ENTITY_TYPE_SOURCES = new ArrayList<>();
    private static final List<Consumer<EntityRendererSink>>
        PENDING_ENTITY_RENDERERS = new ArrayList<>();

    // NF-PARITY W12 (2026-08-25): configuration-phase state. FORGE 26.3: the payload half is live like the play half —
    // the receivers NeoForge bakes into its registrar lambdas are kept in these two maps and looked up at receive
    // time; the configuration-start handlers run from the deferred gate task (onRegisterConfigurationTasks).
    private static final Map<CustomPacketPayload.Type<?>, ClientConfigPayloadHandler<?>>
        CONFIG_CLIENT_HANDLERS = new ConcurrentHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, ServerConfigPayloadHandler<?>>
        CONFIG_SERVER_HANDLERS = new ConcurrentHashMap<>();
    private static final List<PlatformHelper.ServerConfigurationStartHandler>
        CONFIG_START_HANDLERS = new ArrayList<>();

    /** Renderer-registration sink for {@link #drainEntityRendererRegistrations}. */
    @FunctionalInterface
    public interface EntityRendererSink {
        <E extends Entity> void accept(EntityType<? extends E> entityType, EntityRendererProvider<E> provider);
    }

    // FORGE 26.3: one id may live in ONE of the four registries — findCodec answers by id alone (the dispatch codec
    // knows the id, not the phase), so a second phase/direction for the same id would silently shadow the first.
    // Re-registering into the SAME registry stays the idempotent overwrite it is on NeoForge (a Map.put there too).
    private static synchronized <C extends StreamCodec<?, ?>> void putCodec(
            Map<Identifier, C> registry, CustomPacketPayload.Type<?> type, C codec) {
        Identifier id = type.id();
        for (Map<Identifier, ? extends StreamCodec<?, ?>> other : ALL_CODEC_REGISTRIES) {
            if (other != registry && other.containsKey(id)) {
                throw new IllegalStateException(
                    "[SeamlessPortals/Forge] payload id " + id + " is already registered for another phase/direction; "
                        + "ForgePlatformHelper.findCodec resolves codecs by id alone, so ids must be unique across "
                        + "play-clientbound, play-serverbound, configuration-clientbound and configuration-serverbound");
            }
        }
        registry.put(id, codec);
    }

    /**
     * FORGE 26.3: the codec registered for {@code id} in ANY of the four registries, else {@code null} (vanilla's and
     * Forge's own resolution then proceeds untouched). Called by {@code MixinCustomPacketPayloadCodecForge} from the
     * HEAD of {@code CustomPacketPayload$1.findCodec(Identifier)} — on every custom-payload encode and decode, from
     * network threads, hence the concurrent maps.
     */
    public static StreamCodec<?, ?> findCodec(Identifier id) {
        StreamCodec<?, ?> codec = PLAY_CLIENTBOUND.get(id);
        if (codec == null) {
            codec = PLAY_SERVERBOUND.get(id);
        }
        if (codec == null) {
            codec = CONFIG_CLIENTBOUND.get(id);
        }
        if (codec == null) {
            codec = CONFIG_SERVERBOUND.get(id);
        }
        return codec;
    }

    @Override
    public void sendToClient(ServerPlayer player, CustomPacketPayload payload) {
        // FORGE 26.3: replaces NeoForge's PacketDistributor.sendToPlayer(player, payload) with its net effect (and
        // Fabric's ServerPlayNetworking.send): the vanilla packet, sent by the LISTENER — so it passes
        // ServerCommonPacketListenerImpl.send and IP's redirect / force-bundle hooks like every other packet.
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // FORGE 26.3: replaces NeoForge's ClientPacketDistributor.sendToServer(payload) with its net effect —
        // Minecraft.getConnection()Lnet/minecraft/client/multiplayer/ClientPacketListener; then the public
        // ClientCommonPacketListenerImpl.send(Lnet/minecraft/network/protocol/Packet;)V. Client types stay inside
        // this body (exact-type calls only), which only a client ever executes.
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientboundPayload(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        putCodec(PLAY_CLIENTBOUND, type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerboundPayload(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        putCodec(PLAY_SERVERBOUND, type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerPayloadHandler(
            CustomPacketPayload.Type<T> type, ServerPayloadHandler<T> handler) {
        // Looked up at receive time, so handlers may be registered before or
        // after the payload type itself.
        SERVER_HANDLERS.put(type, handler);
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientPayloadHandler(
            CustomPacketPayload.Type<T> type, ClientPayloadHandler<T> handler) {
        CLIENT_HANDLERS.put(type, handler);
    }

    @Override
    public void registerEntityTypes(Consumer<BiConsumer<Identifier, EntityType<?>>> registrationSource) {
        PENDING_ENTITY_TYPE_SOURCES.add(registrationSource);
    }

    // ==== NF-PARITY W13 (2026-08-25): chunk-sent seams, Forge binding ====================

    @Override
    public net.minecraft.network.protocol.Packet<?> decorateChunkPacket(
            net.minecraft.world.level.chunk.LevelChunk chunk,
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet) {
        // FORGE 26.3: replaces NeoForge's chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(packet). Forge has
        // no auxiliary light manager: javap -c of the Forge-patched PlayerChunkSender.sendChunk sends the bare
        // `new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)` — so, as on Fabric,
        // the packet goes out undecorated.
        return packet;
    }

    @Override
    public void onChunkSentToPlayer(
            net.minecraft.server.network.ServerGamePacketListenerImpl listener,
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.chunk.LevelChunk chunk) {
        // C6 guard, carried over from the NeoForge twin: there it keeps NeoForge's chunk-attachment receiver (which
        // resolves against player.level()) from mis-applying a REMOTE-dimension chunk's attachments. Forge's event
        // carries the level explicitly (ChunkWatchEvent$Watch.getLevel()), but every subscriber written against
        // vanilla's flow has only ever seen it for the player's OWN level, so the same narrow, documented deviation is
        // kept: remote-dim chunk sends are not announced.
        if (level.dimension() != listener.player.level().dimension()) {
            return;
        }
        try {
            // FORGE 26.3: replaces NeoForge's EventHooks.fireChunkSent (ChunkWatchEvent.Sent). Forge has no Sent event;
            // what its own PlayerChunkSender.sendChunk does after the send is ForgeEventFactory.fireChunkWatch(
            // Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/
            // server/level/ServerLevel;)V -> ChunkWatchEvent$Watch.BUS.post (javap -c @61). This fires from inside
            // IP's own send loop (PacketRedirection.withForceRedirect); a throwing subscriber must not abort it.
            net.minecraftforge.event.ForgeEventFactory.fireChunkWatch(listener.player, chunk, level);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.error(
                "[NF-PARITY W13] a ChunkWatchEvent.Watch subscriber threw during an IP chunk send", t);
        }
    }

    // ==== NF-PARITY W12 (2026-08-25): configuration-phase seams, Forge binding ===========

    @Override
    public <T extends CustomPacketPayload> void registerConfigClientboundPayload(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.FriendlyByteBuf, T> codec,
            ClientConfigPayloadHandler<T> handler) {
        // FORGE 26.3: replaces the queued registrar.configurationToClient(type, codec, (payload, context) ->
        // handler.handle(payload, context::reply)) — codec and receiver go live at once; the receiver is invoked by
        // dispatchClientbound with the reply sender built there.
        putCodec(CONFIG_CLIENTBOUND, type, codec);
        CONFIG_CLIENT_HANDLERS.put(type, handler);
    }

    @Override
    public <T extends CustomPacketPayload> void registerConfigServerboundPayload(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.FriendlyByteBuf, T> codec,
            ServerConfigPayloadHandler<T> handler) {
        // FORGE 26.3: see registerConfigClientboundPayload; the ServerConfigContext NeoForge builds in its registrar
        // lambda is built by dispatchServerbound.
        putCodec(CONFIG_SERVERBOUND, type, codec);
        CONFIG_SERVER_HANDLERS.put(type, handler);
    }

    @Override
    public void onServerConfigurationStart(ServerConfigurationStartHandler handler) {
        CONFIG_START_HANDLERS.add(handler);
    }

    /**
     * Global-bus listener (subscribed by {@code SeamlessPortalsModForge}, at LOWEST priority). Fires per
     * connection when the server assembles its configuration tasks.
     *
     * <p>FORGE 26.3: replaces NeoForge's mod-bus {@code RegisterConfigurationTasksEvent}, which fires AFTER NeoForge's
     * modded-network negotiation (so its {@code hasChannel} answers at once). Forge's only task-adding hook,
     * {@code GatherLoginConfigurationTasksEvent} ({@code getConnection()}, {@code addTask(ConfigurationTask)}, static
     * {@code BUS}), is posted as the FIRST instruction of the patched
     * {@code ServerConfigurationPacketListenerImpl.startConfiguration} (javap -c @18) — BEFORE any negotiation, which
     * Forge runs INSIDE the configuration phase as tasks gathered by this same event
     * ({@code ForgeNetworkConfigurationHandler.gatherInit}: RegisterChannelsTask, ModVersionsTask, ChannelVersionsTask,
     * SyncRegistriesTask, SyncConfigTask). Nothing about the client is known here, so the seam's handlers are NOT run
     * here: exactly ONE deferred gate task is queued instead, and runs them when it STARTS — by then the server-side
     * {@code ForgePacketHandler.handleModVersions} has filled {@code NetworkContext.modList} from the client's reply and
     * only then finished ModVersionsTask. The gate is queued after Forge's five because this listener is LOWEST and
     * Forge's is NORMAL ({@code ForgeMod.<init>}: {@code BUS.addListener(Consumer)}).
     */
    public static void onRegisterConfigurationTasks(
            net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent event) {
        event.addTask(new ConfigurationStartGate(event.getConnection()));
        // FORGE 26.3: liveness line, once per connection. The gate is the ONLY thing standing between a Forge client
        // and IP's configuration handshake, and a handshake that silently never starts shows up far away (the first
        // Forge world join died decoding ClientboundPlayerPositionPacket, "found 20 bytes extra": the server appends
        // the dimension key, the client only reads it once the handshake set its server version).
        SeamlessPortalsConstants.LOGGER.info(
            "[Forge F-NET] configuration-start gate queued (memoryConnection={}, startHandlers={})",
            event.getConnection().isMemoryConnection(), CONFIG_START_HANDLERS.size());
    }

    /**
     * FORGE 26.3: the deferred gate (no NeoForge counterpart — see {@link #onRegisterConfigurationTasks}). When it
     * starts it runs the configuration-start handlers with a control whose {@code addTask} CAPTURES the task instead of
     * queueing it (Forge's queue is private and already being drained). A captured task is started in the gate's place
     * and the gate reports the captured task's {@code type()} from then on, so the consumer's later
     * {@code finishTask(capturedType)} completes THIS queue entry: {@code finishCurrentTask} compares the requested type
     * against {@code currentTask.type()} read at finish time (javap -c @0-@26), and {@code tick()} likewise re-reads it.
     * With nothing captured the gate finishes at once under its own type.
     */
    private static final class ConfigurationStartGate implements net.minecraft.server.network.ConfigurationTask {

        private static final net.minecraft.server.network.ConfigurationTask.Type TYPE =
            new net.minecraft.server.network.ConfigurationTask.Type("seamlessportals:configuration_start_gate");

        private final net.minecraft.network.Connection connection;
        private volatile net.minecraft.server.network.ConfigurationTask captured;

        private ConfigurationStartGate(net.minecraft.network.Connection connection) {
            this.connection = connection;
        }

        private net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener() {
            return (net.minecraft.server.network.ServerConfigurationPacketListenerImpl) connection.getPacketListener();
        }

        private void runConfigurationStartHandlers() {
            PlatformHelper.ServerConfigStartControl control = new PlatformHelper.ServerConfigStartControl() {
                @Override
                public boolean canSend(CustomPacketPayload.Type<?> type) {
                    // FORGE 26.3: replaces listener.hasChannel(type). With no Forge channel there is no per-payload
                    // negotiation to ask; what Forge HAS negotiated by now is the client's mod list —
                    // NetworkContext.get(Connection).getModList()Ljava/util/Map;, keyed by mod id (ModVersions.create:
                    // toMap(IModInfo::getModId, ..)). A client that runs this mod can receive every payload it
                    // registers. A memory connection is the same JVM (Forge does negotiate over it too — the client
                    // always sends the marked host name and MemoryServerHandshakePacketListenerImpl.handleIntention
                    // calls ServerLifecycleHooks.handleServerLogin — so the first clause is belt and braces).
                    return connection.isMemoryConnection()
                        || NetworkContext.get(connection).getModList().containsKey(SeamlessPortalsConstants.MOD_ID);
                }

                @Override
                public void addTask(net.minecraft.server.network.ConfigurationTask task) {
                    if (captured != null) {
                        throw new IllegalStateException(
                            "[SeamlessPortals/Forge] a second configuration task (" + task.type()
                                + ") was added at configuration start; the Forge binding's deferred gate can stand in "
                                + "for exactly one (already holding " + captured.type() + ")");
                    }
                    captured = task;
                }

                @Override
                public void disconnect(net.minecraft.network.chat.Component reason) {
                    listener().disconnect(reason);
                }

                @Override
                public com.mojang.authlib.GameProfile gameProfile() {
                    return listener().getOwner();
                }
            };
            net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            for (PlatformHelper.ServerConfigurationStartHandler handler : CONFIG_START_HANDLERS) {
                handler.onConfigure(control, server);
            }
            // FORGE 26.3: the second liveness line (see onRegisterConfigurationTasks) — what the gate decided.
            SeamlessPortalsConstants.LOGGER.info(
                "[Forge F-NET] configuration-start gate started: startHandlers={} capturedTask={}",
                CONFIG_START_HANDLERS.size(), captured == null ? "none" : captured.type().id());
        }

        // FORGE 26.3: the variant Forge calls — startNextTask: ConfigurationTask.start(Lnet/minecraftforge/network/
        // config/ConfigurationTaskContext;)V. The captured task gets the same context (its default body forwards to
        // start(Consumer) with context::send, which is how IP's vanilla-shaped task sends its packet).
        @Override
        public void start(net.minecraftforge.network.config.ConfigurationTaskContext context) {
            runConfigurationStartHandlers();
            if (captured != null) {
                captured.start(context);
            } else {
                context.finish(TYPE);
            }
        }

        // The vanilla-shaped variant (abstract on the interface; Forge's listener never calls it).
        @Override
        public void start(Consumer<net.minecraft.network.protocol.Packet<?>> sender) {
            runConfigurationStartHandlers();
            if (captured != null) {
                captured.start(sender);
            } else {
                listener().finishCurrentTask(TYPE);
            }
        }

        @Override
        public boolean tick() {
            return captured != null && captured.tick();
        }

        @Override
        public net.minecraft.server.network.ConfigurationTask.Type type() {
            return captured != null ? captured.type() : TYPE;
        }
    }

    @Override
    public <E extends Entity> void registerEntityRenderer(
            EntityType<? extends E> entityType, EntityRendererProvider<E> rendererProvider) {
        PENDING_ENTITY_RENDERERS.add(sink -> sink.accept(entityType, rendererProvider));
    }

    /**
     * Replays every queued entity-type registration source into {@code sink}.
     * Called from the Forge registry event (entity-type window) by
     * {@code SeamlessPortalsModForge}.
     */
    public static void drainEntityTypeRegistrations(BiConsumer<Identifier, EntityType<?>> sink) {
        PENDING_ENTITY_TYPE_SOURCES.forEach(source -> source.accept(sink));
    }

    /**
     * Replays every queued entity-renderer registration into {@code sink}.
     * Called from the Forge client renderer-registration event
     * ({@code EntityRenderersEvent.RegisterRenderers}) by
     * {@code SeamlessPortalsClientForge}.
     */
    public static void drainEntityRendererRegistrations(EntityRendererSink sink) {
        PENDING_ENTITY_RENDERERS.forEach(reg -> reg.accept(sink));
    }

    // ==== FORGE 26.3: receive side (replaces NeoForge's registerQueuedClientbound / registerQueuedServerbound registrar
    // lambdas; same lookups). Called by the *_PayloadDispatchForge mixins from the HEAD of the listeners'
    // handleCustomPayload, before ForgeHooks.onCustomPayload (client: invokestatic @8, ahead of
    // ensureRunningOnSameThread @36; server: @8). True = the payload's type is one of this mod's (it has a registered
    // codec), and the caller cancels vanilla's/Forge's handling.
    //
    // THREADING = Fabric API's, read off fabric-networking-api-v1 6.3.8 (javap -c): AbstractChanneledNetworkAddon.handle
    // throws RunningOnDifferentThreadException when !isOnReceiveThread(), and the listener mixins catch it with
    // `packetProcessor().scheduleIfPossible(listener, packet)` + cancel. isOnReceiveThread() is
    // `Minecraft.packetProcessor().isSameThread()` (ClientPlayNetworkAddon), `MinecraftServer.packetProcessor()
    // .isSameThread()` (ServerPlayNetworkAddon) and constant `true` for both configuration addons. So a PLAY payload is
    // re-queued on the SAME PacketProcessor every vanilla packet goes through and handled when the processor re-delivers
    // the packet (this HEAD runs a second time, on the main thread) — in SEND ORDER relative to every other packet,
    // which is what IP relies on: the server sends the dimension-int-id sync payload before the first redirected chunk
    // packet, and common's PacketRedirectionClient.handleAtPacketHandle re-queues redirected packets on that same
    // processor. The first Forge binding used Minecraft.execute / MinecraftServer.execute instead — a DIFFERENT queue
    // (the BlockableEventLoop task list), drained at another point of the tick — and the first world join that got past
    // the configuration handshake died of exactly that inversion: "NullPointerException: Client dim id record is not
    // yet synced" in PacketRedirectionClient.handleRedirectedPacket, under PacketProcessor.processQueuedPackets, with
    // the dim-id sync still waiting in the other queue ("Network Protocol Error" on screen). A CONFIGURATION payload
    // runs inline on the network thread, as on Fabric. ====

    /**
     * Clientbound dispatch. The client-only types stay in signatures and exact-type calls — the class still links on a
     * dedicated server, the way the NeoForge twin's {@code Minecraft.getInstance()} lambda does.
     */
    public static boolean dispatchClientbound(
            ClientboundCustomPayloadPacket packet,
            net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl listener) {
        CustomPacketPayload payload = packet.payload();
        CustomPacketPayload.Type<? extends CustomPacketPayload> type = payload.type();
        if (CONFIG_CLIENTBOUND.containsKey(type.id())) {
            runClientConfigHandler(type, payload, listener);
            return true;
        }
        if (PLAY_CLIENTBOUND.containsKey(type.id())) {
            runClientPlayHandler(type, packet, listener);
            return true;
        }
        return false;
    }

    /** Serverbound dispatch — see {@link #dispatchClientbound}. */
    public static boolean dispatchServerbound(
            ServerboundCustomPayloadPacket packet,
            net.minecraft.server.network.ServerCommonPacketListenerImpl listener) {
        CustomPacketPayload payload = packet.payload();
        CustomPacketPayload.Type<? extends CustomPacketPayload> type = payload.type();
        if (CONFIG_SERVERBOUND.containsKey(type.id())) {
            runServerConfigHandler(type, payload, listener);
            return true;
        }
        if (PLAY_SERVERBOUND.containsKey(type.id())) {
            runServerPlayHandler(type, packet, listener);
            return true;
        }
        return false;
    }

    private static <T extends CustomPacketPayload> void runClientPlayHandler(
            CustomPacketPayload.Type<?> type, ClientboundCustomPayloadPacket packet,
            net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl listener) {
        @SuppressWarnings("unchecked")
        ClientPayloadHandler<T> handler = (ClientPayloadHandler<T>) CLIENT_HANDLERS.get(type);
        if (handler != null) {
            Minecraft client = Minecraft.getInstance();
            // FORGE 26.3: Fabric's receive-thread rule (see the section header) — Minecraft.packetProcessor()
            // Lnet/minecraft/network/PacketProcessor;, PacketProcessor.isSameThread()Z, PacketProcessor
            // .scheduleIfPossible(Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/protocol/Packet;)V —
            // all present in forge-26.3-66.0.2.jar (javap).
            if (!client.packetProcessor().isSameThread()) {
                // network pass: re-queue the PACKET in vanilla order; the processor re-delivers it to this HEAD.
                client.packetProcessor()
                    .<net.minecraft.network.protocol.common.ClientCommonPacketListener>scheduleIfPossible(
                        listener, packet);
            } else {
                // main pass: the handler runs inline, on the client main thread (Fabric-parity threading).
                @SuppressWarnings("unchecked")
                T payload = (T) packet.payload();
                handler.handle(payload, client);
            }
        }
        // A registered type with no handler is a silent no-op, as on NeoForge.
    }

    private static <T extends CustomPacketPayload> void runServerPlayHandler(
            CustomPacketPayload.Type<?> type, ServerboundCustomPayloadPacket packet,
            net.minecraft.server.network.ServerCommonPacketListenerImpl listener) {
        @SuppressWarnings("unchecked")
        ServerPayloadHandler<T> handler = (ServerPayloadHandler<T>) SERVER_HANDLERS.get(type);
        // FORGE 26.3: replaces (ServerPlayer) context.player() — the play listener's public `player` field. A play
        // payload that reaches a non-play listener has no player to hand over and is dropped.
        if (handler != null
            && listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gameListener) {
            ServerPlayer player = gameListener.player;
            net.minecraft.server.MinecraftServer server = player.level().getServer();
            // FORGE 26.3: Fabric's receive-thread rule, server half (see the section header) —
            // MinecraftServer.packetProcessor()Lnet/minecraft/network/PacketProcessor; (javap, Forge jar).
            if (!server.packetProcessor().isSameThread()) {
                // network pass: re-queue the PACKET in vanilla order; the processor re-delivers it to the play
                // listener's HEAD (MixinServerGamePacketListenerImpl_PayloadDispatchForge).
                server.packetProcessor()
                    .<net.minecraft.network.protocol.common.ServerCommonPacketListener>scheduleIfPossible(
                        listener, packet);
            } else {
                // main pass: the handler runs inline, on the server main thread (Fabric-parity threading).
                @SuppressWarnings("unchecked")
                T payload = (T) packet.payload();
                handler.handle(payload, player);
            }
        }
    }

    private static <T extends CustomPacketPayload> void runClientConfigHandler(
            CustomPacketPayload.Type<?> type, CustomPacketPayload rawPayload,
            net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl listener) {
        @SuppressWarnings("unchecked")
        ClientConfigPayloadHandler<T> handler = (ClientConfigPayloadHandler<T>) CONFIG_CLIENT_HANDLERS.get(type);
        if (handler != null) {
            @SuppressWarnings("unchecked")
            T payload = (T) rawPayload;
            // NETWORK thread, inline = NeoForge's executesOn(HandlerThread.NETWORK) = Fabric's config-receiver
            // threading (the handshake replies mid-configuration).
            // FORGE 26.3: replaces context::reply — the listener's own public send(Packet) with the vanilla packet.
            handler.handle(payload, reply -> listener.send(new ServerboundCustomPayloadPacket(reply)));
        }
    }

    private static <T extends CustomPacketPayload> void runServerConfigHandler(
            CustomPacketPayload.Type<?> type, CustomPacketPayload rawPayload,
            net.minecraft.server.network.ServerCommonPacketListenerImpl listener) {
        @SuppressWarnings("unchecked")
        ServerConfigPayloadHandler<T> handler = (ServerConfigPayloadHandler<T>) CONFIG_SERVER_HANDLERS.get(type);
        if (handler != null) {
            @SuppressWarnings("unchecked")
            T payload = (T) rawPayload;
            handler.handle(payload, new ServerConfigContext() {
                @Override
                public com.mojang.authlib.GameProfile gameProfile() {
                    // getOwner() is public on ServerCommonPacketListenerImpl in the Forge jar too.
                    return listener.getOwner();
                }

                @Override
                public void finishTask(net.minecraft.server.network.ConfigurationTask.Type taskType) {
                    // FORGE 26.3: replaces context.finishCurrentTask(taskType) —
                    // ServerConfigurationPacketListenerImpl.finishCurrentTask(ConfigurationTask$Type)V, public in the
                    // Forge jar. For IP's handshake the current task is the deferred gate, reporting IP's type.
                    ((net.minecraft.server.network.ServerConfigurationPacketListenerImpl) listener)
                        .finishCurrentTask(taskType);
                }

                @Override
                public void disconnect(net.minecraft.network.chat.Component reason) {
                    listener.disconnect(reason);
                }
            });
        }
    }

    @Override
    public void registerPayloads() {
        // NF-PARITY W7 (2026-08-25): the COMPLETE block-era payload TYPE set, mirroring
        // FabricPlatformHelper.registerPayloads' 19 registrations (NeoForge's checkPacket THROWS on an
        // unregistered type where Fabric silently drops; on Forge an unregistered type fails at ENCODE —
        // vanilla's fallback codec cannot write it). Registered through the seams with map-lookup
        // handlers (no-op until a handler is registered — the block-era handler SETS remain
        // Fabric-only, the recorded flag-OFF deviation). The 4 payloads with live
        // handlers keep their direct registrations in onRegisterPayloadHandlers and are NOT
        // duplicated here.
        registerClientboundPayload(ModPayloads.RedirectedChunkPayload.TYPE, ModPayloads.RedirectedChunkPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.PortalLinkPayload.TYPE, ModPayloads.PortalLinkPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.ClientboundSeamlessMovePayload.TYPE, ModPayloads.ClientboundSeamlessMovePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.SpeculativePrewarmScopePayload.TYPE, ModPayloads.SpeculativePrewarmScopePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteBlockUpdatePayload.TYPE, ModPayloads.RemoteBlockUpdatePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityAddPayload.TYPE, ModPayloads.RemoteEntityAddPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityMovePayload.TYPE, ModPayloads.RemoteEntityMovePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityRemovePayload.TYPE, ModPayloads.RemoteEntityRemovePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityDataPayload.TYPE, ModPayloads.RemoteEntityDataPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityEquipmentPayload.TYPE, ModPayloads.RemoteEntityEquipmentPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.PortalUnregisterPayload.TYPE, ModPayloads.PortalUnregisterPayload.STREAM_CODEC);
        // ★ SEAM OCCUPANCY (RS passthrough, flag-ON feature; NF-PARITY 2026-08-30). Registered
        // unconditionally like every type here — types are flag-neutral, the senders self-gate.
        registerClientboundPayload(ModPayloads.SeamOccupancyPayload.TYPE, ModPayloads.SeamOccupancyPayload.STREAM_CODEC);
        registerServerboundPayload(ModPayloads.RequestPortalDataPayload.TYPE, ModPayloads.RequestPortalDataPayload.STREAM_CODEC);
        registerServerboundPayload(ModPayloads.ClientPortalCrossingPayload.TYPE, ModPayloads.ClientPortalCrossingPayload.STREAM_CODEC);
        registerServerboundPayload(ModPayloads.RedirectedChunkAckPayload.TYPE, ModPayloads.RedirectedChunkAckPayload.STREAM_CODEC);

        SeamlessPortalsConstants.LOGGER.info(
            "Forge network payloads registered live (20 types: 19 block-era + seam occupancy; no drain — see onRegisterPayloadHandlers)");
    }

    // FORGE 26.3: replaces NeoForge's mod-bus RegisterPayloadHandlersEvent listener — Forge has no such event, and under
    // F-NET there is no registrar to drain into: the queued seam registrations are already live. What is left of the
    // NeoForge method is its five DIRECT registrations, kept here (subscribed to FMLCommonSetupEvent by
    // SeamlessPortalsModForge) so the class keeps the NeoForge twin's shape. Their handlers lose the explicit
    // context.enqueueWork hop: the dispatcher above already runs play handlers on the main thread.
    public static void onRegisterPayloadHandlers(FMLCommonSetupEvent event) {
        // Server -> Client
        putCodec(PLAY_CLIENTBOUND, ModPayloads.PortalSyncPayload.TYPE, ModPayloads.PortalSyncPayload.STREAM_CODEC);
        putCodec(PLAY_CLIENTBOUND, ModPayloads.RemoteChunkDataPayload.TYPE, ModPayloads.RemoteChunkDataPayload.STREAM_CODEC);
        putCodec(PLAY_CLIENTBOUND, ModPayloads.RemoteChunkUnloadPayload.TYPE, ModPayloads.RemoteChunkUnloadPayload.STREAM_CODEC);
        putCodec(PLAY_CLIENTBOUND, ModPayloads.RemoteBlockUpdateBatchPayload.TYPE, ModPayloads.RemoteBlockUpdateBatchPayload.STREAM_CODEC);
        // FORGE 26.3: the four CLIENT receivers are created on Dist.CLIENT only. A ClientPayloadHandler's interface
        // method takes net.minecraft.client.Minecraft, so LINKING a lambda/method ref for it (LambdaMetafactory resolves
        // the method types) loads that class — NeoForge's IPayloadContext handlers did not. An invokedynamic links at
        // first execution, so the guarded block costs a dedicated server nothing; the codec rows above must exist on
        // both dists (the server encodes these payloads).
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CLIENT_HANDLERS.put(ModPayloads.PortalSyncPayload.TYPE,
                (ClientPayloadHandler<ModPayloads.PortalSyncPayload>) ForgePlatformHelper::handlePortalSync);
            CLIENT_HANDLERS.put(ModPayloads.RemoteChunkDataPayload.TYPE,
                (ClientPayloadHandler<ModPayloads.RemoteChunkDataPayload>) ForgePlatformHelper::handleRemoteChunkData);
            CLIENT_HANDLERS.put(ModPayloads.RemoteChunkUnloadPayload.TYPE,
                (ClientPayloadHandler<ModPayloads.RemoteChunkUnloadPayload>) ForgePlatformHelper::handleRemoteChunkUnload);
            CLIENT_HANDLERS.put(ModPayloads.RemoteBlockUpdateBatchPayload.TYPE,
                (ClientPayloadHandler<ModPayloads.RemoteBlockUpdateBatchPayload>) ForgePlatformHelper::handleRemoteBlockUpdateBatch);
        }

        // Client -> Server
        putCodec(PLAY_SERVERBOUND, ModPayloads.PortalTeleportPayload.TYPE, ModPayloads.PortalTeleportPayload.STREAM_CODEC);
        SERVER_HANDLERS.put(ModPayloads.PortalTeleportPayload.TYPE,
            (ServerPayloadHandler<ModPayloads.PortalTeleportPayload>) ForgePlatformHelper::handlePortalTeleport);

        // S0 seam drain: NONE on Forge — payload types registered through the loader-neutral
        // registerClientboundPayload/registerServerboundPayload seams went live when they were registered.

        // NF-PARITY W12: configuration-phase payloads — likewise live (registerConfigClientboundPayload /
        // registerConfigServerboundPayload). NETWORK-thread receivers = Fabric's config-receiver threading; NeoForge's
        // .optional() has no counterpart to need: without a Forge channel nothing about these ids enters Forge's
        // negotiation, so a client WITHOUT the mod reaches IP's own reject/warn policy
        // (serverRejectClientWithoutImmPtl) through the deferred gate's canSend.

        SeamlessPortalsConstants.LOGGER.info("Forge network payloads registered");
    }

    // FORGE 26.3 (the five handlers below): IPayloadContext is gone — they are ClientPayloadHandler /
    // ServerPayloadHandler bodies now, and the context.enqueueWork(..) wrapper each had on NeoForge is the dispatcher's
    // job (runClientPlayHandler / runServerPlayHandler), so the bodies run on the same threads as before.

    private static void handlePortalSync(ModPayloads.PortalSyncPayload payload, Minecraft client) {
        SeamlessPortalsConstants.LOGGER.debug("Received portal sync: {}", payload.portalId());
    }

    private static void handleRemoteChunkData(ModPayloads.RemoteChunkDataPayload payload, Minecraft client) {
        RemoteChunkManager.handleChunkData(
            payload.dimensionId(), payload.chunkX(), payload.chunkZ(), payload.chunkData()
        );
    }

    private static void handleRemoteChunkUnload(ModPayloads.RemoteChunkUnloadPayload payload, Minecraft client) {
        RemoteChunkManager.handleChunkUnload(
            payload.dimensionId(), payload.chunkX(), payload.chunkZ()
        );
    }

    private static void handlePortalTeleport(ModPayloads.PortalTeleportPayload payload, ServerPlayer sender) {
        SeamlessPortalsConstants.LOGGER.debug("Received teleport confirmation from client: {}", payload.portalId());
    }

    private static void handleRemoteBlockUpdateBatch(
            ModPayloads.RemoteBlockUpdateBatchPayload payload, Minecraft client) {
        com.warwa.seamlessportals.chunk.RemoteBlockUpdater.applyBatch(
            payload.dimensionId(), payload.positions(), payload.blockStateIds());
    }
}
