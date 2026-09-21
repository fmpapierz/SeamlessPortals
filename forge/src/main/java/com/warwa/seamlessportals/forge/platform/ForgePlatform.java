package com.warwa.seamlessportals.forge.platform;

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
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.JarInJarDependencyLocator;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.DataPackRegistryEvent;
import net.minecraftforge.registries.RegisterEvent;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * 26.3 FORGE PORT: the MinecraftForge binding of {@link Platform} — the twin of {@code NeoForgePlatform} (NF-PARITY
 * W8). Global-bus events register immediately (legal at any time on Forge: every event owns a static {@code BUS});
 * the two REGISTRY hooks queue into the static lists below and are drained by {@code SeamlessPortalsModForge}'s
 * listeners ({@link #drainDataPackRegistries} at {@code DataPackRegistryEvent.NewRegistry},
 * {@link #drainArgumentTypeRegistrations} at {@code RegisterEvent} for
 * {@code COMMAND_ARGUMENT_TYPE}) — callers therefore must invoke them during init, before
 * those windows fire, which every loader's init sequence already guarantees.
 *
 * <p>Every API here was verified with {@code javap} against forge-26.3-66.0.2.jar (the Mavenizer output),
 * fmlcore/fmlloader/javafmllanguage 26.3-66.0.2, forgespi 8.0.0 and eventbus 7.0.6 — each deviation from the NeoForge
 * twin carries a {@code // FORGE 26.3:} note naming the member it relies on.
 */
public class ForgePlatform implements Platform {

    // ==== registry-window queues (drained by SeamlessPortalsModForge) ====

    private static final List<Consumer<DataPackRegistryEvent.NewRegistry>>
        PENDING_DATAPACK_REGISTRIES = new ArrayList<>();
    private static final List<Consumer<RegisterEvent>>
        PENDING_ARGUMENT_TYPES = new ArrayList<>();

    /** Called from the {@code DataPackRegistryEvent.NewRegistry} listener. */
    public static void drainDataPackRegistries(DataPackRegistryEvent.NewRegistry event) {
        PENDING_DATAPACK_REGISTRIES.forEach(reg -> reg.accept(event));
    }

    /** Called from the mod-bus {@code RegisterEvent} listener (COMMAND_ARGUMENT_TYPE window). */
    public static void drainArgumentTypeRegistrations(RegisterEvent event) {
        PENDING_ARGUMENT_TYPES.forEach(reg -> reg.accept(event));
        // FORGE 26.3: liveness line — the NeoForge twin's drain had ZERO callers until 2026-09-20 and nothing said so.
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
            "[Forge] command argument types registered into COMMAND_ARGUMENT_TYPE: {}",
            PENDING_ARGUMENT_TYPES.size());
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
        // FORGE 26.3: replaces NeoForge's FMLEnvironment.getDist() — Forge exposes the dist as a FIELD
        // (fmlloader FMLEnvironment: `public static final Dist dist`); Dist.isDedicatedServer()Z is mergetool-api 1.0.
        return FMLEnvironment.dist.isDedicatedServer();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        // FORGE 26.3: replaces NeoForge's FMLEnvironment.isProduction() — a FIELD on Forge
        // (fmlloader FMLEnvironment: `public static final boolean production`).
        return !FMLEnvironment.production;
    }

    @Override
    public boolean isForgeLike() {
        return true;
    }

    // ==== mod list ====
    // FORGE 26.3 (whole section): NeoForge's ModList.get().xxx() instance calls are STATIC on Forge 66 — fmlcore
    // ModList has a private ctor and no get(): `public static boolean isLoaded(String)`,
    // `public static Optional<? extends ModContainer> getModContainerById(String)`,
    // `public static List<IModInfo> getMods()`. IModInfo is forgespi 8.0.0 (net.minecraftforge.forgespi.language).

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.isLoaded(modId);
    }

    @Override
    public Optional<ModVersionInfo> getModVersion(String modId) {
        return ModList.getModContainerById(modId).map(container -> {
            ArtifactVersion version = container.getModInfo().getVersion();
            // The Forge translation of IP's "regular form" test: plain major.minor.patch
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
        return ModList.getModContainerById(modId)
            .map(container -> OptionalInt.of(container.getModInfo().getVersion()
                .compareTo(new DefaultArtifactVersion(versionStr))))
            .orElse(OptionalInt.empty());
    }

    // FORGE 26.3 (isModNested + isTopLevel): replaces NeoForge's
    // `getOwningFile().getFile().getDiscoveryAttributes().parent() != null`. forgespi 8.0.0 IModFile has NO discovery
    // attributes and no parent accessor; the one thing a jar-in-jar mod file carries is its PROVIDER: fmlloader
    // JarInJarDependencyLocator.scanMods builds every nested file through the inherited
    // AbstractModProvider.createMod(Path, boolean, String), which passes `this` to `new ModFile(SecureJar,
    // IModProvider, ModFileInfoParser, String)` (javap -c), and IModFile.getProvider()Lnet/minecraftforge/forgespi/
    // locating/IModProvider; hands it back. Top-level files come from ModsFolderLocator / ClasspathLocator /
    // MinecraftLocator / the dev locators instead.

    @Override
    public boolean isModNested(String modId) {
        return ModList.getModContainerById(modId)
            .map(container -> container.getModInfo().getOwningFile().getFile()
                .getProvider() instanceof JarInJarDependencyLocator)
            .orElse(false);
    }

    @Override
    public int getTopLevelModCount() {
        return (int) ModList.getMods().stream()
            .filter(ForgePlatform::isTopLevel)
            .count();
    }

    private static boolean isTopLevel(IModInfo info) {
        return !(info.getOwningFile().getFile().getProvider() instanceof JarInJarDependencyLocator);
    }

    @Override
    public Optional<String> getModIconPath(String modId) {
        return ModList.getModContainerById(modId)
            .flatMap(container -> container.getModInfo().getLogoFile());
    }

    @Override
    public Optional<String> getModDisplayName(String modId) {
        return ModList.getModContainerById(modId)
            .map(container -> container.getModInfo().getDisplayName());
    }

    @Override
    public java.util.List<String> getLoadedModIds() {
        return ModList.getMods().stream()
            .map(IModInfo::getModId).sorted().toList();
    }

    // ==== registries ====

    @Override
    public <T> void registerDataPackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        // Queued: NewRegistry fires once, at Forge's CREATE_REGISTRIES state; the mod class drains at the right window.
        // FORGE 26.3: same member as NeoForge's — DataPackRegistryEvent$NewRegistry.dataPackRegistry(ResourceKey,
        // Codec)V. What differs is the BUS (static, not the mod bus) — see the subscription in SeamlessPortalsModForge.
        PENDING_DATAPACK_REGISTRIES.add(event -> event.dataPackRegistry(key, codec));
    }

    @Override
    public <A extends com.mojang.brigadier.arguments.ArgumentType<?>,
            T extends ArgumentTypeInfo.Template<A>,
            I extends ArgumentTypeInfo<A, T>>
    void registerArgumentType(Identifier id, Class<A> clazz, I info) {
        // Half 1, immediate: populate the BY_CLASS map. FORGE 26.3: the SAME patched-in public static exists on Forge
        // — ArgumentTypeInfos.registerByClass(Ljava/lang/Class;Lnet/minecraft/commands/synchronization/
        // ArgumentTypeInfo;)Lnet/minecraft/commands/synchronization/ArgumentTypeInfo; (public static synchronized, no
        // timing constraint) — so this line is unchanged.
        ArgumentTypeInfos.registerByClass(clazz, info);
        // Half 2, queued: the COMMAND_ARGUMENT_TYPE registry entry must land inside the
        // RegisterEvent window. FORGE 26.3: RegisterEvent.register(ResourceKey, Identifier, Supplier)V has NeoForge's
        // exact shape; on Forge it is also the ONLY legal route — COMMAND_ARGUMENT_TYPE is a ForgeRegistry-wrapped
        // registry (GameData.init: ForgeRegistries.Keys.COMMAND_ARGUMENT_TYPES) whose vanilla face is locked.
        PENDING_ARGUMENT_TYPES.add(event ->
            event.register(Registries.COMMAND_ARGUMENT_TYPE, id, () -> info));
    }

    @Override
    public CreativeModeTab.Builder createCreativeTabBuilder() {
        // The Forge-patched no-arg builder. FORGE 26.3: CreativeModeTab.builder()Lnet/minecraft/world/item/
        // CreativeModeTab$Builder; is public static in the Forge jar too.
        return CreativeModeTab.builder();
    }

    @Override
    public void setChunkGeneratorFeaturesPerStep(
        net.minecraft.world.level.chunk.ChunkGenerator generator,
        java.util.function.Supplier<java.util.List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData>> featuresPerStep
    ) {
        // FORGE 26.3: overrides the Platform default (the common accessor mixin IEChunkGenerator_AlternateDim), which
        // cannot bind on Forge because Forge retypes ChunkGenerator.featuresPerStep to its own ClearableLazy. The
        // forge module's duck does the write instead — why it has to be a plain interface outside every mixin package
        // is on com.warwa.seamlessportals.forge.duck.ChunkGeneratorFeaturesPerStepForge's javadoc.
        ((com.warwa.seamlessportals.forge.duck.ChunkGeneratorFeaturesPerStepForge) generator)
            .seamlessportals$setFeaturesPerStep(featuresPerStep);
    }

    // ==== lifecycle events (global bus) ====
    // FORGE 26.3 (whole section): NeoForge's single game bus `NeoForge.EVENT_BUS.addListener(..)` does not exist in
    // EventBus 7. Every event class owns its bus: `XEvent.BUS.addListener(Consumer)` (eventbus 7.0.6
    // EventBus.addListener(Ljava/util/function/Consumer;)Lnet/minecraftforge/eventbus/api/listener/EventListener;).

    @Override
    public void onServerTickEnd(Consumer<MinecraftServer> listener) {
        // FORGE 26.3: TickEvent$ServerTickEvent$Post is a record — the server accessor is server(), not getServer().
        TickEvent.ServerTickEvent.Post.BUS.addListener(
            (TickEvent.ServerTickEvent.Post event) -> listener.accept(event.server()));
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        // FORGE 26.3: ServerStartedEvent is a record whose component is literally named getServer -> getServer().
        ServerStartedEvent.BUS.addListener(
            (ServerStartedEvent event) -> listener.accept(event.getServer()));
    }

    // FORGE 26.3: listeners of onServerDataPackReloadEnd, fired by MixinMinecraftServer_DataPackReloadEndForge (server
    // thread). CopyOnWrite: registered during init, iterated on the server thread.
    private static final List<Consumer<MinecraftServer>> DATAPACK_RELOAD_END_LISTENERS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called by {@code MixinMinecraftServer_DataPackReloadEndForge} once a {@code /reload} future has settled. */
    public static void fireServerDataPackReloadEnd(MinecraftServer server) {
        for (Consumer<MinecraftServer> listener : DATAPACK_RELOAD_END_LISTENERS) {
            listener.accept(server);
        }
    }

    @Override
    public void onServerDataPackReloadEnd(Consumer<MinecraftServer> listener) {
        // Fabric's END_DATA_PACK_RELOAD has no Forge twin: Forge 66 has ONE TagsUpdatedEvent record and posts it from
        // the CLIENT only — a constant-pool scan of the whole jar finds ForgeEventFactory.onTagsUpdated referenced by
        // net/minecraft/client/multiplayer/ClientPacketListener and nothing else, so NeoForge's
        // TagsUpdatedEvent.ServerDataLoad route would be dead here.
        // FORGE 26.3, RESOLVED 2026-09-20 (user decision): the first binding listened for OnDatapackSyncEvent with a
        // NULL player — what PlayerList.reloadResources() happens to post at the end of /reload — a Forge-internal
        // convention. It is replaced by Fabric API's own implementation, shape for shape, as a forge-module mixin
        // (MixinMinecraftServer_DataPackReloadEndForge: TAIL of MinecraftServer.reloadResources ->
        // returnedFuture.handleAsync(.., server)): server thread, on success and on failure, never at boot (boot is
        // onServerStarted's job on every loader). Listeners must be idempotent (the Platform javadoc contract).
        DATAPACK_RELOAD_END_LISTENERS.add(listener);
    }

    @Override
    public void onRegisterServerCommands(ServerCommandRegistrar registrar) {
        RegisterCommandsEvent.BUS.addListener((RegisterCommandsEvent event) ->
            registrar.register(event.getDispatcher(), event.getBuildContext()));
    }

    @Override
    public void onAttackBlock(AttackBlockHandler handler) {
        // Fabric fires its callback once per attack; Forge fires LeftClickBlock up to 4x
        // (START/STOP/ABORT/CLIENT_HOLD). Filter to START = Fabric's semantic.
        // FORGE 26.3: replaces NeoForge's event.setCanceled(true). EventBus 7 has no cancel flag on the event:
        // PlayerInteractEvent$LeftClickBlock.BUS is a CancellableEventBus and a listener cancels by RETURNING TRUE
        // from addListener(Ljava/util/function/Predicate;). A cancelled post makes ForgeEventFactory.onLeftClickBlock
        // return null and ServerPlayerGameMode.handleBlockBreakAction return at once (javap -c: `ifnonnull` / `return`)
        // — the break never starts = Fabric's FAIL.
        PlayerInteractEvent.LeftClickBlock.BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> {
            if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
                return false;
            }
            if (!handler.allowAttack(event.getEntity(), event.getEntity().level(),
                    event.getHand(), event.getPos(), event.getFace())) {
                return true;
            }
            return false;
        });
    }
}
