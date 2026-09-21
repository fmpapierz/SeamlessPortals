// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig;

import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.minecraft.client.gui.screens.Screen;

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
 */
public class AutoConfig {
    public static <T extends ConfigData> ConfigHolder<T> register(
        Class<T> configClass,
        ConfigSerializer.Factory<T> serializerFactory
    ) {
        Config definition = configClass.getAnnotation(Config.class);
        ConfigSerializer<T> serializer = serializerFactory.create(definition, configClass);
        return new ConfigManager<>(serializer);
    }

    public static <T extends ConfigData> Supplier<Screen> getConfigScreen(Class<T> configClass, Screen parent) {
        // Cloth-Config GUI screen — S19-deferred (no Cloth-Config GUI build on MC 26.2).
        return null;
    }
}
