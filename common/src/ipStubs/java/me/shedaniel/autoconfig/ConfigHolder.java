// F21 Cloth-Config / AutoConfig compileOnly stub — see ConfigData.java header. NOT shipped. Removed at S20.
package me.shedaniel.autoconfig;

import net.minecraft.world.InteractionResult;

/**
 * Held config handle (IPGlobal.configHolder). Only the members IP touches are declared:
 * getConfig / setConfig / save (IPConfig.getConfig, onConfigChanged) and registerSaveListener
 * (IPModMain.initConfig — the save listener returns {@link InteractionResult}, matching Cloth-Config's
 * mojmap SaveEvent return type).
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
