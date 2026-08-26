package com.warwa.seamlessportals.fabric.platform;

import com.warwa.seamlessportals.platform.ClientPlatform;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.Consumer;

/**
 * NF-PARITY W8 (2026-08-25): the Fabric binding of {@link ClientPlatform}. Direct
 * delegations to the exact Fabric API calls the {@code :common} sites used before the
 * facade rewrite.
 */
public class FabricClientPlatform implements ClientPlatform {

    @Override
    public void onClientTickEnd(Consumer<Minecraft> listener) {
        ClientTickEvents.END_CLIENT_TICK.register(listener::accept);
    }

    @Override
    public void onClientPlayJoin(Consumer<ClientPacketListener> listener) {
        ClientPlayConnectionEvents.JOIN.register(
            (handler, sender, client) -> listener.accept(handler));
    }

    @Override
    public void onNewConnectionStateReset(Runnable listener) {
        // IP's original timing: login INIT, before the configuration phase — so the reset
        // can never wipe a value the configuration handshake just delivered.
        ClientLoginConnectionEvents.INIT.register((handler, client) -> listener.run());
    }

    @Override
    public void registerSecondaryWorldReloadListener(
            net.minecraft.server.packs.resources.PreparableReloadListener listener) {
        // Vanilla-legal on Fabric: the exact direct registration ClientWorldLoader made
        // before the NF-PARITY seam (moved verbatim; no freeze exists here).
        ((net.minecraft.server.packs.resources.ReloadableResourceManager)
            Minecraft.getInstance().getResourceManager()).registerReloadListener(listener);
    }

    @Override
    public void postClientChunkLoadEvent(ClientLevel level, LevelChunk chunk) {
        ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk);
    }

    @Override
    public void postClientChunkUnloadEvent(ClientLevel level, LevelChunk chunk) {
        ClientChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(level, chunk);
    }

    @Override
    public void onRegisterClientCommands(ClientCommandRegistrationHandler handler) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            handler.register(dispatcher, new ClientCommandSupport<FabricClientCommandSource>() {
                @Override
                public void sendFeedback(FabricClientCommandSource source, Component message) {
                    source.sendFeedback(message);
                }
            }));
    }
}
