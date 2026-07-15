// F21 Cloth-Config / AutoConfig compileOnly stub — see ConfigData.java header. NOT shipped. Removed at S20.
package me.shedaniel.autoconfig;

import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

/**
 * Static entry point. Only the two members IP touches are declared:
 * {@code register(Class, ConfigSerializer.Factory)} (IPModMain.initConfig — {@code GsonConfigSerializer::new}
 * binds as the {@link ConfigSerializer.Factory}) and {@code getConfigScreen(Class, Screen)} (IPConfigGUI —
 * returns a {@link Supplier} the caller resolves with {@code .get()}).
 */
public class AutoConfig {
    public static <T extends ConfigData> ConfigHolder<T> register(
        Class<T> configClass,
        ConfigSerializer.Factory<T> serializerFactory
    ) {
        return null;
    }

    public static <T extends ConfigData> Supplier<Screen> getConfigScreen(Class<T> configClass, Screen parent) {
        return null;
    }
}
