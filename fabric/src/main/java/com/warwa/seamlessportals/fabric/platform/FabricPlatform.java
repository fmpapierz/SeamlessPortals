package com.warwa.seamlessportals.fabric.platform;

import com.warwa.seamlessportals.platform.ModVersionInfo;
import com.warwa.seamlessportals.platform.Platform;
import com.mojang.serialization.Codec;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * NF-PARITY W8 (2026-08-25): the Fabric binding of {@link Platform}. Every method is a
 * direct delegation to the exact Fabric API call the {@code :common} site used before the
 * facade rewrite — Fabric behavior is unchanged by construction.
 */
public class FabricPlatform implements Platform {

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isDedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isForgeLike() {
        return false;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Optional<ModVersionInfo> getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(container -> {
            Version version = container.getMetadata().getVersion();
            // IP's "regular form" test verbatim (O_O.getImmPtlVersion): a semantic version
            // with exactly 3 components. Anything else (e.g. the dev-env "${version}"
            // placeholder) reports isRegularSemantic=false -> ModVersion.OTHER upstream.
            if (version instanceof SemanticVersion semantic
                && semantic.getVersionComponentCount() == 3) {
                return new ModVersionInfo(
                    semantic.getVersionComponent(0),
                    semantic.getVersionComponent(1),
                    semantic.getVersionComponent(2),
                    true,
                    version.toString()
                );
            }
            return new ModVersionInfo(0, 0, 0, false, version.toString());
        });
    }

    @Override
    public OptionalInt compareModVersionTo(String modId, String versionStr) {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modId);
        if (container.isEmpty()) {
            return OptionalInt.empty();
        }
        Version installed = container.get().getMetadata().getVersion();
        try {
            Version given = Version.parse(versionStr);
            return OptionalInt.of(installed.compareTo(given));
        } catch (VersionParsingException e) {
            e.printStackTrace();
            return OptionalInt.empty();
        }
    }

    @Override
    public boolean isModNested(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getContainingMod().isPresent())
            .orElse(false);
    }

    @Override
    public int getTopLevelModCount() {
        return (int) FabricLoader.getInstance().getAllMods().stream()
            .filter(modContainer -> modContainer.getContainingMod().isEmpty())
            .count();
    }

    @Override
    public Optional<String> getModIconPath(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .flatMap(container -> container.getMetadata().getIconPath(512));
    }

    @Override
    public Optional<String> getModDisplayName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getMetadata().getName());
    }

    @Override
    public java.util.List<String> getLoadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream()
            .map(c -> c.getMetadata().getId()).sorted().toList();
    }

    @Override
    public <T> void registerDataPackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        DynamicRegistries.register(key, codec);
    }

    @Override
    public <A extends com.mojang.brigadier.arguments.ArgumentType<?>,
            T extends ArgumentTypeInfo.Template<A>,
            I extends ArgumentTypeInfo<A, T>>
    void registerArgumentType(Identifier id, Class<A> clazz, I info) {
        ArgumentTypeRegistry.registerArgumentType(id, clazz, info);
    }

    @Override
    public CreativeModeTab.Builder createCreativeTabBuilder() {
        return FabricCreativeModeTab.builder();
    }

    @Override
    public void onServerTickEnd(Consumer<MinecraftServer> listener) {
        ServerTickEvents.END_SERVER_TICK.register(listener::accept);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STARTED.register(listener::accept);
    }

    @Override
    public void onServerDataPackReloadEnd(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
            (server, resourceManager, success) -> listener.accept(server));
    }

    @Override
    public void onRegisterServerCommands(ServerCommandRegistrar registrar) {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, ctx, environment) -> registrar.register(dispatcher, ctx));
    }

    @Override
    public void onAttackBlock(AttackBlockHandler handler) {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
            handler.allowAttack(player, world, hand, pos, direction)
                ? InteractionResult.PASS
                : InteractionResult.FAIL);
    }
}
