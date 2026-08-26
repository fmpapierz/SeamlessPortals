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
