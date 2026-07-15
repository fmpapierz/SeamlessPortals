// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig;

import me.shedaniel.autoconfig.serializer.ConfigSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * The shipped {@link ConfigHolder} implementation (Cloth-Config's ConfigManager analogue). Loads the
 * config through its {@link ConfigSerializer} on construction and persists it on {@link #save()},
 * firing any registered save listeners exactly as Cloth does. Package-private — obtained only through
 * {@link AutoConfig#register}.
 */
final class ConfigManager<T extends ConfigData> implements ConfigHolder<T> {
    private final ConfigSerializer<T> serializer;
    private final List<SaveEvent<T>> saveListeners = new ArrayList<>();
    private T config;

    ConfigManager(ConfigSerializer<T> serializer) {
        this.serializer = serializer;
        // deserialize() never throws — a missing/corrupt file yields a fresh default.
        this.config = serializer.deserialize();
    }

    @Override
    public T getConfig() {
        return config;
    }

    @Override
    public void setConfig(T config) {
        this.config = config;
    }

    @Override
    public boolean save() {
        serializer.serialize(config);
        for (SaveEvent<T> listener : saveListeners) {
            try {
                listener.onSave(this, config);
            }
            catch (Throwable ignored) {
                // a misbehaving listener must not break persistence
            }
        }
        return true;
    }

    @Override
    public void registerSaveListener(SaveEvent<T> save) {
        saveListeners.add(save);
    }
}
