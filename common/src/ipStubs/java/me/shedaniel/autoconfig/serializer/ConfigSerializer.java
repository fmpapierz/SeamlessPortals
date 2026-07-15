// F21 Cloth-Config / AutoConfig compileOnly stub — see ../ConfigData.java header. NOT shipped. Removed at S20.
package me.shedaniel.autoconfig.serializer;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

/**
 * Serializer contract. IP never calls its methods directly — only the nested {@link Factory} functional
 * interface is touched ({@code GsonConfigSerializer::new} in IPModMain.initConfig), so the interface body
 * declares no abstract members (GsonConfigSerializer implements it trivially).
 */
public interface ConfigSerializer<T extends ConfigData> {
    @FunctionalInterface
    interface Factory<T extends ConfigData> {
        ConfigSerializer<T> create(Config definition, Class<T> configClass);
    }
}
