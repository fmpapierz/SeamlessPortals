// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ConfigData.java header; S13-B P-1).
// FORGE 26.3: plus real AutoConfig's holder registry (register -> getConfigHolder). The native config screen
// (AutoConfigClient -> me.shedaniel.autoconfig.gui.NativeConfigScreen) needs the LIVE holder of a config class — to
// read the current values, write the edited ones back and call save() — and register() used to return the only
// reference to it.
package me.shedaniel.autoconfig;

import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Static entry point. Only the two members IP touches are implemented:
 *
 * <ul>
 *   <li>{@code register(Class, ConfigSerializer.Factory)} (IPModMain.loadConfig — {@code
 *       GsonConfigSerializer::new} binds as the {@link ConfigSerializer.Factory}). Returns a live
 *       {@link ConfigHolder} that has already LOADED the config from disk, so the immediately-following
 *       {@code registerSaveListener} / {@code getConfig} calls work and flag-ON boot no longer NPEs.</li>
 *   <li>{@code getConfigScreen(Class, Screen)} (IPConfigGUI — the Cloth-Config GUI). Cloth's GUI has no
 *       MC 26.2 build; the in-game config SCREEN is S19-deferred (same disposition as the dim_stack GUI
 *       shells). Returns {@code null} exactly as the compileOnly stub did — this method is client-only
 *       and never on the flag-ON first-light path (it is reached only by the {@code /portal} config-GUI
 *       command). The {@code Screen} type appears only in this method's descriptor, so loading AutoConfig
 *       on a dedicated server never resolves it (register() touches no client type).</li>
 * </ul>
 *
 * <p>FORGE 26.3: a third member, {@link #getConfigHolder(Class)} — real AutoConfig's lookup of the holder
 * {@code register} created, same name and signature. Nothing in IP calls it; this module's own
 * {@link AutoConfigClient} does, to hand the holder to the native config screen. It names no client type (this class
 * IS loaded on a dedicated server, by {@code register}), so the descriptor note above still describes the class's
 * only client reference. {@code getConfigScreen} here stays the unused 26.2-era shell: since Cloth Config 26.x the
 * screen entry point IPConfigGUI calls is {@link AutoConfigClient#getConfigScreen}.
 */
public class AutoConfig {
    // FORGE 26.3: real AutoConfig's `holders` map. Concurrent because register() runs from mod loading (IPModMain.init,
    // inside the first RegisterEvent) while the lookup happens on the client thread much later, and nothing here
    // assumes those are the same thread.
    private static final Map<Class<? extends ConfigData>, ConfigHolder<?>> holders = new ConcurrentHashMap<>();

    public static <T extends ConfigData> ConfigHolder<T> register(
        Class<T> configClass,
        ConfigSerializer.Factory<T> serializerFactory
    ) {
        Config definition = configClass.getAnnotation(Config.class);
        ConfigSerializer<T> serializer = serializerFactory.create(definition, configClass);
        // FORGE 26.3: the holder is remembered as well as returned (see `holders`). Real AutoConfig throws on a second
        // registration of the same class; this one lets the latest registration win — nothing in this package is
        // allowed to be able to break boot (ConfigData.java header).
        ConfigManager<T> manager = new ConfigManager<>(serializer);
        holders.put(configClass, manager);
        return manager;
    }

    // FORGE 26.3: real AutoConfig's getConfigHolder — same contract, including the RuntimeException for a class that
    // was never registered. The one caller (AutoConfigClient.getConfigScreen) is only reached with the entity-portal
    // flag ON, the state in which IPModMain.loadConfig has registered IPConfig (the same precondition the Fabric
    // ModMenuIntegration documents for real Cloth).
    @SuppressWarnings("unchecked")
    public static <T extends ConfigData> ConfigHolder<T> getConfigHolder(Class<T> configClass) {
        ConfigHolder<?> holder = holders.get(configClass);
        if (holder == null) {
            throw new RuntimeException(String.format("Config '%s' has not been registered", configClass));
        }
        return (ConfigHolder<T>) holder;
    }

    public static <T extends ConfigData> Supplier<Screen> getConfigScreen(Class<T> configClass, Screen parent) {
        // Cloth-Config GUI screen — S19-deferred (no Cloth-Config GUI build on MC 26.2).
        return null;
    }
}
