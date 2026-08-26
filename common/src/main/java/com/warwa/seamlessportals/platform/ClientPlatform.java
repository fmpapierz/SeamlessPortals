package com.warwa.seamlessportals.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * NF-PARITY W8 (2026-08-25): the CLIENT-ONLY half of the platform facade — see
 * {@link Platform} for the pattern. Split from {@code Platform} deliberately: this
 * interface's signatures reference client-only vanilla types ({@code Minecraft},
 * {@code ClientLevel}, {@code ClientPacketListener}), and NeoForge has NO
 * {@code @Environment}-style bytecode stripping on a dedicated server — keeping the client
 * types out of {@code Platform} means server code can link the common half without ever
 * resolving these. Only ever loaded from client code paths.
 */
public interface ClientPlatform {

    static ClientPlatform get() {
        return Holder.INSTANCE;
    }

    // ==== lifecycle events ====

    /** End of every client tick (Fabric: {@code ClientTickEvents.END_CLIENT_TICK}; NeoForge: {@code ClientTickEvent.Post} — note NeoForge gates fires on {@code gameLoadFinished}, harmless because {@code mc.level} is null pre-title). */
    void onClientTickEnd(Consumer<Minecraft> listener);

    /** The local player joined a world on a new connection (Fabric: {@code ClientPlayConnectionEvents.JOIN}; NeoForge: {@code ClientPlayerNetworkEvent.LoggingIn}). */
    void onClientPlayJoin(Consumer<ClientPacketListener> listener);

    /**
     * Reset per-server-connection state so nothing stales across reconnects. Fabric fires
     * this at login INIT (before the configuration phase — the exact IP timing); NeoForge
     * has no login/configuration connection event, so it fires at
     * {@code ClientPlayerNetworkEvent.LoggingOut} instead — the OTHER side of the same gap,
     * chosen because firing at {@code LoggingIn} (PLAY phase) would wipe the configuration
     * handshake value that was just received (recon C1: the obvious mapping is wrong).
     * Listeners must only RESET state here, never read it.
     */
    void onNewConnectionStateReset(Runnable listener);

    // ==== secondary-world resource reloads ====

    /**
     * Registers a reload listener for a SECONDARY portal-view dimension's resources
     * (extractor / cloud renderer), callable at ANY time — including mid-session when a
     * secondary ClientLevel is first created. Fabric: the vanilla-legal direct
     * {@code ReloadableResourceManager.registerReloadListener}. NeoForge FREEZES that list
     * after {@code AddClientReloadListenersEvent} (late registration throws
     * UnsupportedOperationException — the measured L6 world-join crash), so its binding
     * appends to a live list behind ONE early-registered forwarding listener that
     * multiplexes the preparation barrier.
     */
    void registerSecondaryWorldReloadListener(
        net.minecraft.server.packs.resources.PreparableReloadListener listener);

    // ==== event producers (for third-party mod compat) ====

    /** Posts the loader's client-chunk-LOAD event ({@code O_O.postClientChunkLoadEvent} — re-emitted because {@code ImmPtlClientChunkMap} overrides the vanilla/loader post sites). */
    void postClientChunkLoadEvent(ClientLevel level, LevelChunk chunk);

    /** Posts the loader's client-chunk-UNLOAD event. */
    void postClientChunkUnloadEvent(ClientLevel level, LevelChunk chunk);

    // ==== client commands ====

    /**
     * Client command registration. The handler is invoked with the loader's own
     * client-command source type {@code <S>} plus a feedback adapter — this is how
     * {@code ClientDebugCommand} stays one generic file instead of two loader copies
     * (Fabric: {@code FabricClientCommandSource::sendFeedback}; NeoForge:
     * {@code ClientCommandSourceStack.sendSuccess}).
     */
    void onRegisterClientCommands(ClientCommandRegistrationHandler handler);

    interface ClientCommandRegistrationHandler {
        <S> void register(CommandDispatcher<S> dispatcher, ClientCommandSupport<S> support);
    }

    /** Loader-specific operations on the client command source. */
    interface ClientCommandSupport<S> {
        void sendFeedback(S source, Component message);
    }

    class Holder {
        private static final ClientPlatform INSTANCE = ServiceLoader.load(ClientPlatform.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No ClientPlatform implementation found"));
    }
}
