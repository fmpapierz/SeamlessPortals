// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ../ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig.serializer;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

/**
 * Serializer contract. The shipped {@link GsonConfigSerializer} implements the three persistence
 * operations; the nested {@link Factory} is what IP binds ({@code GsonConfigSerializer::new} in
 * IPModMain.loadConfig). None of these methods throw — any I/O failure falls back to defaults / a
 * best-effort no-op so config resolution can never destabilize flag-ON boot.
 */
public interface ConfigSerializer<T extends ConfigData> {
    /** Persist the config to its backing file (best-effort; never throws). */
    void serialize(T config);

    /** Load the config from its backing file, returning a fresh default if absent/unreadable. */
    T deserialize();

    /** A fresh default-constructed config instance. */
    T createDefault();

    @FunctionalInterface
    interface Factory<T extends ConfigData> {
        ConfigSerializer<T> create(Config definition, Class<T> configClass);
    }
}
