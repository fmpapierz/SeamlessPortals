package com.warwa.seamlessportals.platform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * NF-PARITY W8 (2026-08-25): the loader-neutral platform facade for {@code :common} —
 * server-safe half. Everything here is callable from BOTH dists; client-only surface lives
 * in {@link ClientPlatform} (a separate ServiceLoader so a NeoForge dedicated server never
 * links a client type through this class).
 *
 * <p>Same pattern and policy as {@code com.warwa.seamlessportals.network.PlatformHelper}
 * (S0): SEAMS for the ported IP code — never Fabric types in common. Each loader module
 * binds an implementation via {@code META-INF/services/}. The Fabric impl is a one-line
 * delegation per method to the exact Fabric API call the {@code :common} site used before
 * the W8 rewrite, so Fabric behavior is unchanged by construction.
 *
 * <p><b>Event-registration timing contract:</b> the {@code on*} methods may be called at any
 * point during mod init (Fabric registers immediately; NeoForge adds a game-bus listener,
 * legal at any time). The two registry hooks ({@link #registerDataPackRegistry},
 * {@link #registerArgumentType}) are the exception — on NeoForge they QUEUE and are drained
 * inside the proper mod-bus event window, so callers must invoke them during init (both
 * loaders' init sequences already do).
 */
public interface Platform {

    static Platform get() {
        return Holder.INSTANCE;
    }

    // ==== paths & environment ====

    Path getGameDir();

    Path getConfigDir();

    /** True only on a dedicated server (Fabric: {@code EnvType.SERVER}; NeoForge: {@code Dist.DEDICATED_SERVER}). */
    boolean isDedicatedServer();

    /** Fabric: {@code isDevelopmentEnvironment()}; NeoForge: {@code !FMLEnvironment.isProduction()}. */
    boolean isDevelopmentEnvironment();

    /** True on (Neo)Forge-family loaders — drives IP's forge-vs-fabric info-URL choice ({@code O_O.isForge}). */
    boolean isForgeLike();

    // ==== mod list ====

    boolean isModLoaded(String modId);

    /** The mod's version, or empty if the mod is absent. */
    Optional<ModVersionInfo> getModVersion(String modId);

    /**
     * Compares the INSTALLED version of {@code modId} to {@code version} (parsed with the
     * loader's own version scheme). Empty if the mod is absent or the string is unparseable.
     * Result sign: installed &lt; given → negative; equal → 0; installed &gt; given → positive.
     */
    OptionalInt compareModVersionTo(String modId, String version);

    /** Whether {@code modId} is provided nested inside another mod (jar-in-jar). */
    boolean isModNested(String modId);

    /** Count of top-level (non-nested) mods — drives IP's "many mods" warning. */
    int getTopLevelModCount();

    /**
     * The mod's icon/logo path inside its jar, if declared (Fabric:
     * {@code metadata.getIconPath(512)}; NeoForge: {@code IModInfo.getLogoFile()} — no size
     * variants there, so the size hint is Fabric-only).
     */
    Optional<String> getModIconPath(String modId);

    /** The mod's display name, or empty if absent. */
    Optional<String> getModDisplayName(String modId);

    /** All loaded mod ids, sorted (drives the debug mod-list dump). */
    java.util.List<String> getLoadedModIds();

    // ==== registries / registration windows ====

    /**
     * Registers an unsynced data-pack registry (Fabric: {@code DynamicRegistries.register};
     * NeoForge: {@code DataPackRegistryEvent.NewRegistry#dataPackRegistry} — queued, drained
     * in the mod-bus event).
     */
    <T> void registerDataPackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec);

    /**
     * Registers a command argument type (Fabric: {@code ArgumentTypeRegistry.registerArgumentType};
     * NeoForge: {@code ArgumentTypeInfos.registerByClass} + a queued
     * {@code BuiltInRegistries.COMMAND_ARGUMENT_TYPE} registration drained in RegisterEvent).
     */
    <A extends com.mojang.brigadier.arguments.ArgumentType<?>,
     T extends ArgumentTypeInfo.Template<A>,
     I extends ArgumentTypeInfo<A, T>>
    void registerArgumentType(Identifier id, Class<A> clazz, I info);

    /** A vanilla {@code CreativeModeTab.Builder} (Fabric: {@code FabricCreativeModeTab.builder()}; NeoForge: the patched no-arg {@code CreativeModeTab.builder()}). */
    CreativeModeTab.Builder createCreativeTabBuilder();

    // ==== lifecycle events (game bus) ====

    /** End of every server tick (Fabric: {@code ServerTickEvents.END_SERVER_TICK}; NeoForge: {@code ServerTickEvent.Post}). */
    void onServerTickEnd(Consumer<MinecraftServer> listener);

    /** Server finished starting (Fabric: {@code ServerLifecycleEvents.SERVER_STARTED}; NeoForge: {@code ServerStartedEvent}). */
    void onServerStarted(Consumer<MinecraftServer> listener);

    /**
     * A data-pack reload completed on the running server (Fabric:
     * {@code ServerLifecycleEvents.END_DATA_PACK_RELOAD}; NeoForge:
     * {@code TagsUpdatedEvent.ServerDataLoad} + {@code ServerLifecycleHooks.getCurrentServer()},
     * which may double-fire alongside {@link #onServerStarted} at boot — every registered
     * listener must be idempotent, as {@code CustomPortalGenManager.onDataPackReloaded}
     * already is).
     */
    void onServerDataPackReloadEnd(Consumer<MinecraftServer> listener);

    /** Server command registration (Fabric: {@code CommandRegistrationCallback}; NeoForge: {@code RegisterCommandsEvent}). */
    void onRegisterServerCommands(ServerCommandRegistrar registrar);

    /**
     * Player left-clicks (attacks) a block. Return FALSE to cancel the break (Fabric:
     * {@code AttackBlockCallback} returning {@code InteractionResult.FAIL}; NeoForge:
     * {@code PlayerInteractEvent.LeftClickBlock} + {@code setCanceled(true)}, filtered to
     * the START action so NeoForge's 4-fires-per-interaction collapses to Fabric's one).
     */
    void onAttackBlock(AttackBlockHandler handler);

    @FunctionalInterface
    interface ServerCommandRegistrar {
        void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext);
    }

    @FunctionalInterface
    interface AttackBlockHandler {
        /** @return false to cancel the block break, true to allow vanilla to proceed */
        boolean allowAttack(Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction);
    }

    class Holder {
        private static final Platform INSTANCE = ServiceLoader.load(Platform.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No Platform implementation found"));
    }
}
