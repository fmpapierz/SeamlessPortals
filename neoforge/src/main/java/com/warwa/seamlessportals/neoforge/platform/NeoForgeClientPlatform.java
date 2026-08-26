package com.warwa.seamlessportals.neoforge.platform;

import com.warwa.seamlessportals.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.function.Consumer;

/**
 * NF-PARITY W8 (2026-08-25): the NeoForge binding of {@link ClientPlatform}. Loaded only
 * from client code paths (the ServiceLoader holder is touched lazily).
 */
public class NeoForgeClientPlatform implements ClientPlatform {

    @Override
    public void onClientTickEnd(Consumer<Minecraft> listener) {
        // Delta vs Fabric (recorded): NeoForge gates both tick events on gameLoadFinished
        // (Minecraft.java:1816/:1918) — harmless here, mc.level is null pre-title.
        NeoForge.EVENT_BUS.addListener(
            (ClientTickEvent.Post event) -> listener.accept(Minecraft.getInstance()));
    }

    @Override
    public void onClientPlayJoin(Consumer<ClientPacketListener> listener) {
        NeoForge.EVENT_BUS.addListener(
            (ClientPlayerNetworkEvent.LoggingIn event) -> listener.accept(event.getPlayer().connection));
    }

    @Override
    public void onNewConnectionStateReset(Runnable listener) {
        // NeoForge has no client login/configuration connection event (recon C1). Firing at
        // LoggingIn (PLAY phase) would wipe the configuration handshake value that was JUST
        // received — so the reset runs at LoggingOut instead: the other side of the same
        // gap, protecting against stale state across reconnects just as Fabric's login-INIT
        // reset does. Listeners only RESET state here (the ClientPlatform contract).
        NeoForge.EVENT_BUS.addListener(
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

    /** Mod-bus hook — subscribed by {@code SeamlessPortalsClientNeoForge}. */
    public static void onAddReloadListeners(
            net.neoforged.neoforge.client.event.AddClientReloadListenersEvent event) {
        event.addListener(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "seamlessportals", "secondary_world_reloads"),
            SECONDARY_FORWARDER);
    }

    @Override
    public void registerSecondaryWorldReloadListener(
            net.minecraft.server.packs.resources.PreparableReloadListener listener) {
        SECONDARY_RELOAD_LISTENERS.add(listener);
    }

    @Override
    public void postClientChunkLoadEvent(ClientLevel level, LevelChunk chunk) {
        // ChunkEvent.Load(chunk, newChunk=false); the level rides on chunk.getLevel().
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
    }

    @Override
    public void postClientChunkUnloadEvent(ClientLevel level, LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
    }

    @Override
    public void onRegisterClientCommands(ClientCommandRegistrationHandler handler) {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
            handler.register(event.getDispatcher(),
                new ClientCommandSupport<CommandSourceStack>() {
                    @Override
                    public void sendFeedback(CommandSourceStack source, Component message) {
                        // ClientCommandSourceStack overrides sendSuccess to route into the
                        // client chat and force sendToAdmins=false (NF
                        // ClientCommandSourceStack.java:58-62), so the broadcast arg is inert.
                        source.sendSuccess(() -> message, false);
                    }
                }));
    }
}
