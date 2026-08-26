package com.warwa.seamlessportals.neoforge.platform;

import com.warwa.seamlessportals.platform.ModVersionInfo;
import com.warwa.seamlessportals.platform.Platform;
import com.mojang.serialization.Codec;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * NF-PARITY W8 (2026-08-25): the NeoForge binding of {@link Platform}. Game-bus events
 * register immediately (legal at any time on NeoForge); the two REGISTRY hooks queue into
 * the static lists below and are drained by {@code SeamlessPortalsModNeoForge}'s mod-bus
 * listeners ({@link #drainDataPackRegistries} at {@code DataPackRegistryEvent.NewRegistry},
 * {@link #drainArgumentTypeRegistrations} at {@code RegisterEvent} for
 * {@code COMMAND_ARGUMENT_TYPE}) — callers therefore must invoke them during init, before
 * those windows fire, which both loaders' init sequences already guarantee.
 *
 * <p>Every API here was verified against FML 11.0.13 ({@code javap} on the resolved loader
 * jar) and the neoforge262-ref sources — see the NF-PARITY port notes.
 */
public class NeoForgePlatform implements Platform {

    // ==== mod-bus queues (drained by SeamlessPortalsModNeoForge) ====

    private static final List<Consumer<DataPackRegistryEvent.NewRegistry>>
        PENDING_DATAPACK_REGISTRIES = new ArrayList<>();
    private static final List<Consumer<RegisterEvent>>
        PENDING_ARGUMENT_TYPES = new ArrayList<>();

    /** Called from the mod-bus {@code DataPackRegistryEvent.NewRegistry} listener. */
    public static void drainDataPackRegistries(DataPackRegistryEvent.NewRegistry event) {
        PENDING_DATAPACK_REGISTRIES.forEach(reg -> reg.accept(event));
    }

    /** Called from the mod-bus {@code RegisterEvent} listener (COMMAND_ARGUMENT_TYPE window). */
    public static void drainArgumentTypeRegistrations(RegisterEvent event) {
        PENDING_ARGUMENT_TYPES.forEach(reg -> reg.accept(event));
    }

    // ==== paths & environment ====

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLEnvironment.getDist().isDedicatedServer();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    @Override
    public boolean isForgeLike() {
        return true;
    }

    // ==== mod list ====

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<ModVersionInfo> getModVersion(String modId) {
        return ModList.get().getModContainerById(modId).map(container -> {
            ArtifactVersion version = container.getModInfo().getVersion();
            // The NeoForge translation of IP's "regular form" test: plain major.minor.patch
            // means no build number and no qualifier (DefaultArtifactVersion never throws;
            // an unparseable string lands entirely in the qualifier -> not regular).
            boolean regular = version.getBuildNumber() == 0 && version.getQualifier() == null;
            return new ModVersionInfo(
                version.getMajorVersion(),
                version.getMinorVersion(),
                version.getIncrementalVersion(),
                regular,
                version.toString()
            );
        });
    }

    @Override
    public OptionalInt compareModVersionTo(String modId, String versionStr) {
        return ModList.get().getModContainerById(modId)
            .map(container -> OptionalInt.of(container.getModInfo().getVersion()
                .compareTo(new DefaultArtifactVersion(versionStr))))
            .orElse(OptionalInt.empty());
    }

    @Override
    public boolean isModNested(String modId) {
        return ModList.get().getModContainerById(modId)
            .map(container -> container.getModInfo().getOwningFile().getFile()
                .getDiscoveryAttributes().parent() != null)
            .orElse(false);
    }

    @Override
    public int getTopLevelModCount() {
        return (int) ModList.get().getMods().stream()
            .filter(NeoForgePlatform::isTopLevel)
            .count();
    }

    private static boolean isTopLevel(IModInfo info) {
        return info.getOwningFile().getFile().getDiscoveryAttributes().parent() == null;
    }

    @Override
    public Optional<String> getModIconPath(String modId) {
        return ModList.get().getModContainerById(modId)
            .flatMap(container -> container.getModInfo().getLogoFile());
    }

    @Override
    public Optional<String> getModDisplayName(String modId) {
        return ModList.get().getModContainerById(modId)
            .map(container -> container.getModInfo().getDisplayName());
    }

    @Override
    public java.util.List<String> getLoadedModIds() {
        return ModList.get().getMods().stream()
            .map(IModInfo::getModId).sorted().toList();
    }

    // ==== registries ====

    @Override
    public <T> void registerDataPackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        // Queued: NewRegistry is a MOD-BUS event; the mod class drains at the right window.
        PENDING_DATAPACK_REGISTRIES.add(event -> event.dataPackRegistry(key, codec));
    }

    @Override
    public <A extends com.mojang.brigadier.arguments.ArgumentType<?>,
            T extends ArgumentTypeInfo.Template<A>,
            I extends ArgumentTypeInfo<A, T>>
    void registerArgumentType(Identifier id, Class<A> clazz, I info) {
        // Half 1, immediate: populate the BY_CLASS map (a NeoForge-patched public static,
        // ArgumentTypeInfos.java:70-82 — no timing constraint, synchronized).
        ArgumentTypeInfos.registerByClass(clazz, info);
        // Half 2, queued: the COMMAND_ARGUMENT_TYPE registry entry must land inside the
        // RegisterEvent unfreeze window.
        PENDING_ARGUMENT_TYPES.add(event ->
            event.register(Registries.COMMAND_ARGUMENT_TYPE, id, () -> info));
    }

    @Override
    public CreativeModeTab.Builder createCreativeTabBuilder() {
        // The NeoForge-patched no-arg builder (CreativeModeTab.java:72-74).
        return CreativeModeTab.builder();
    }

    // ==== lifecycle events (game bus) ====

    @Override
    public void onServerTickEnd(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(
            (ServerTickEvent.Post event) -> listener.accept(event.getServer()));
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(
            (ServerStartedEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public void onServerDataPackReloadEnd(Consumer<MinecraftServer> listener) {
        // Fabric's END_DATA_PACK_RELOAD has no exact NeoForge twin. ServerDataLoad is posted
        // from ReloadableServerResources.loadResources — both at boot and on /reload — and
        // carries no server; ServerLifecycleHooks fills that in. At boot this fires BEFORE
        // ServerStartedEvent (server not yet current -> null guard skips it), so boot-time
        // behavior matches Fabric (SERVER_STARTED drives the first load); on /reload the
        // current server is set and the listener runs. Listeners must be idempotent (the
        // Platform javadoc contract).
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent.ServerDataLoad event) -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                listener.accept(server);
            }
        });
    }

    @Override
    public void onRegisterServerCommands(ServerCommandRegistrar registrar) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
            registrar.register(event.getDispatcher(), event.getBuildContext()));
    }

    @Override
    public void onAttackBlock(AttackBlockHandler handler) {
        // Fabric fires its callback once per attack; NeoForge fires LeftClickBlock up to 4x
        // (START/STOP/ABORT/CLIENT_HOLD). Filter to START = Fabric's semantic. Cancelling
        // forces useBlock/useItem FALSE (PlayerInteractEvent.java:340-347) = Fabric's FAIL.
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> {
            if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
                return;
            }
            if (!handler.allowAttack(event.getEntity(), event.getEntity().level(),
                    event.getHand(), event.getPos(), event.getFace())) {
                event.setCanceled(true);
            }
        });
    }
}
