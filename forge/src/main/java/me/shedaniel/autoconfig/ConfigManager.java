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
        // FORGE 26.3: listeners FIRST, then the file — real Cloth's ConfigManager.save() order (cloth-config 26.x: the
        // save-event loop runs, then serializer.serialize). This shim used to write the file first, which nothing
        // could observe while no screen existed. With the native config screen it can: IPConfig.onConfigChanged (the
        // save listener) clamps typed fields IN PLACE precisely so that "the GUI box and immersive_portals.json agree
        // with what the engine actually runs" (its own words, on portalWindowRenderDistance) — serializing before it
        // ran would persist a typed 50 as 50 while the engine and the reopened screen both say 32. No new reference
        // of any kind; a listener's return value is still ignored, as before.
        for (SaveEvent<T> listener : saveListeners) {
            try {
                listener.onSave(this, config);
            }
            catch (Throwable ignored) {
                // a misbehaving listener must not break persistence
            }
        }
        serializer.serialize(config);
        return true;
    }

    @Override
    public void registerSaveListener(SaveEvent<T> save) {
        saveListeners.add(save);
    }
}
