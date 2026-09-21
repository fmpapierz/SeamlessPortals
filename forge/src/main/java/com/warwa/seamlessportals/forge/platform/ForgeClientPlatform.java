package com.warwa.seamlessportals.forge.platform;

import com.warwa.seamlessportals.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;

import java.util.function.Consumer;

/**
 * 26.3 FORGE PORT: the MinecraftForge binding of {@link ClientPlatform} — the twin of {@code NeoForgeClientPlatform}
 * (NF-PARITY W8). Loaded only from client code paths (the ServiceLoader holder is touched lazily).
 *
 * <p>FORGE 26.3 (whole class): NeoForge's single game bus {@code NeoForge.EVENT_BUS} does not exist in EventBus 7 —
 * every event class owns its bus ({@code XEvent.BUS.addListener(Consumer)} / {@code XEvent.BUS.post(event)}, eventbus
 * 7.0.6 {@code EventBus}); each use below names the javap-verified event it binds.
 */
public class ForgeClientPlatform implements ClientPlatform {

    @Override
    public void onClientTickEnd(Consumer<Minecraft> listener) {
        // Delta vs NeoForge (recorded): Forge does NOT gate its tick events on gameLoadFinished — javap -c of the
        // Forge-patched Minecraft.tick shows ForgeEventFactory.onPreClientTick @55 and onPostClientTick @648 with no
        // guard, i.e. Fabric's END_CLIENT_TICK behavior. Harmless either way, mc.level is null pre-title.
        // FORGE 26.3: replaces NeoForge's ClientTickEvent.Post — TickEvent$ClientTickEvent$Post, a field-less record
        // posted as its INSTANCE singleton on TickEvent$ClientTickEvent$Post.BUS.
        TickEvent.ClientTickEvent.Post.BUS.addListener(
            (TickEvent.ClientTickEvent.Post event) -> listener.accept(Minecraft.getInstance()));
    }

    @Override
    public void onClientPlayJoin(Consumer<ClientPacketListener> listener) {
        // FORGE 26.3: ClientPlayerNetworkEvent$LoggingIn is a record with NeoForge's accessor name —
        // getPlayer()Lnet/minecraft/client/player/LocalPlayer; — fired from ClientPacketListener.handleLogin through
        // ForgeEventFactoryClient.firePlayerLogin (the same funnel).
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
            (ClientPlayerNetworkEvent.LoggingIn event) -> listener.accept(event.getPlayer().connection));
    }

    @Override
    public void onNewConnectionStateReset(Runnable listener) {
        // Forge has no client login/configuration connection event either (recon C1). Firing at
        // LoggingIn (PLAY phase) would wipe the configuration handshake value that was JUST
        // received — so the reset runs at LoggingOut instead: the other side of the same
        // gap, protecting against stale state across reconnects just as Fabric's login-INIT
        // reset does. Listeners only RESET state here (the ClientPlatform contract).
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(
            (ClientPlayerNetworkEvent.LoggingOut event) -> listener.run());
    }

    // ==== secondary-world resource reloads (NF-PARITY L6 fix #2, 2026-08-26) ============
    // NeoForge freezes ReloadableResourceManager's listener list after
    // AddClientReloadListenersEvent (registerReloadListener then THROWS — the measured
    // world-join crash when the first secondary portal-view ClientLevel registered its
    // extractor mid-session). One FORWARDER registers in the event window; the per-dimension
    // listeners append to a live list the forwarder walks at each reload. The preparation
    // barrier is multiplexed with the standard pass-through trick: the forwarder awaits the
    // REAL barrier once, and hands sub-listeners a barrier whose wait() gates on that.
    // FORGE 26.3: Forge does NOT freeze that list — javap -c of the Forge-patched ReloadableResourceManager:
    // `listeners` is a plain Lists.newArrayList() and registerReloadListener is a bare List.add, the vanilla shape —
    // so Fabric's direct late registration would also be legal here. The forwarder is kept so this class stays the
    // line-for-line twin of the NeoForge one; it is equally valid on Forge (one listener registered in Forge's own
    // window, RegisterClientReloadListenersEvent).

    private static final java.util.concurrent.CopyOnWriteArrayList<
        net.minecraft.server.packs.resources.PreparableReloadListener>
        SECONDARY_RELOAD_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final net.minecraft.server.packs.resources.PreparableReloadListener
        SECONDARY_FORWARDER = new net.minecraft.server.packs.resources.PreparableReloadListener() {
            @Override
            public String getName() {
                return "seamlessportals:secondary_world_reloads";
            }

            @Override
            public void prepareSharedState(SharedState currentReload) {
                for (var l : SECONDARY_RELOAD_LISTENERS) {
                    l.prepareSharedState(currentReload);
                }
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> reload(
                SharedState currentReload,
                java.util.concurrent.Executor taskExecutor,
                PreparationBarrier preparationBarrier,
                java.util.concurrent.Executor reloadExecutor
            ) {
                var gate = preparationBarrier.wait(net.minecraft.util.Unit.INSTANCE);
                PreparationBarrier passThrough = new PreparationBarrier() {
                    @Override
                    public <T> java.util.concurrent.CompletableFuture<T> wait(T t) {
                        return gate.thenApply(unit -> t);
                    }
                };
                java.util.List<java.util.concurrent.CompletableFuture<Void>> futures =
                    new java.util.ArrayList<>();
                for (var l : SECONDARY_RELOAD_LISTENERS) {
                    futures.add(l.reload(currentReload, taskExecutor, passThrough, reloadExecutor));
                }
                return java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(java.util.concurrent.CompletableFuture[]::new));
            }
        };

    /** Global-bus hook — subscribed by {@code SeamlessPortalsClientForge}. */
    public static void onAddReloadListeners(
            net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        // FORGE 26.3: replaces NeoForge's AddClientReloadListenersEvent.addListener(Identifier, listener). Forge's
        // RegisterClientReloadListenersEvent.registerReloadListener(Lnet/minecraft/server/packs/resources/
        // PreparableReloadListener;)V takes NO id (it forwards to ReloadableResourceManager.registerReloadListener);
        // the listener keeps its name through getName() above.
        event.registerReloadListener(SECONDARY_FORWARDER);
    }

    @Override
    public void registerSecondaryWorldReloadListener(
            net.minecraft.server.packs.resources.PreparableReloadListener listener) {
        SECONDARY_RELOAD_LISTENERS.add(listener);
    }

    @Override
    public void postClientChunkLoadEvent(ClientLevel level, LevelChunk chunk) {
        // ChunkEvent.Load(chunk, newChunk=false); the level rides on chunk.getLevel().
        // FORGE 26.3: ChunkEvent$Load is a record — <init>(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)V — posted on
        // its own ChunkEvent$Load.BUS; Forge's patched ClientChunkCache posts exactly this, with iconst_0 (javap -c).
        ChunkEvent.Load.BUS.post(new ChunkEvent.Load(chunk, false));
    }

    @Override
    public void postClientChunkUnloadEvent(ClientLevel level, LevelChunk chunk) {
        // FORGE 26.3: ChunkEvent$Unload.<init>(Lnet/minecraft/world/level/chunk/ChunkAccess;)V on ChunkEvent$Unload.BUS.
        ChunkEvent.Unload.BUS.post(new ChunkEvent.Unload(chunk));
    }

    @Override
    public void onRegisterClientCommands(ClientCommandRegistrationHandler handler) {
        // FORGE 26.3: RegisterClientCommandsEvent is a record with NeoForge's accessor name —
        // getDispatcher()Lcom/mojang/brigadier/CommandDispatcher; (of CommandSourceStack).
        RegisterClientCommandsEvent.BUS.addListener((RegisterClientCommandsEvent event) ->
            handler.register(event.getDispatcher(),
                new ClientCommandSupport<CommandSourceStack>() {
                    @Override
                    public void sendFeedback(CommandSourceStack source, Component message) {
                        // Forge's ClientCommandSourceStack overrides sendSuccess to route straight into the
                        // client chat (javap -c: Minecraft.gui.hud.getChat().addClientSystemMessage) and never
                        // reads the broadcast flag, so the arg is inert.
                        source.sendSuccess(() -> message, false);
                    }
                }));
    }
}
