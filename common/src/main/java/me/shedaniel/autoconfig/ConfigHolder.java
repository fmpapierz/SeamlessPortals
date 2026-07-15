// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig;

import net.minecraft.world.InteractionResult;

/**
 * Held config handle (IPGlobal.configHolder). Only the members IP touches are declared:
 * {@code getConfig} / {@code setConfig} / {@code save} (IPConfig.getConfig, IPConfig.saveConfigFile)
 * and {@code registerSaveListener} (IPModMain.loadConfig — the save listener returns
 * {@link InteractionResult}, matching Cloth-Config's mojmap SaveEvent return type). The shipped
 * implementation is {@link ConfigManager}.
 */
public interface ConfigHolder<T extends ConfigData> {
    T getConfig();

    void setConfig(T config);

    boolean save();

    void registerSaveListener(SaveEvent<T> save);

    @FunctionalInterface
    interface SaveEvent<T extends ConfigData> {
        InteractionResult onSave(ConfigHolder<T> holder, T config);
    }
}
