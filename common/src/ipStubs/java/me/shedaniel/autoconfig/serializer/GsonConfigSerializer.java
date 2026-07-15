// F21 Cloth-Config / AutoConfig compileOnly stub — see ../ConfigData.java header. NOT shipped. Removed at S20.
package me.shedaniel.autoconfig.serializer;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

/**
 * The GSON-backed serializer IPModMain references as {@code GsonConfigSerializer::new}, which binds as a
 * {@link ConfigSerializer.Factory} — hence the {@code (Config, Class<T>)} constructor.
 */
public class GsonConfigSerializer<T extends ConfigData> implements ConfigSerializer<T> {
    public GsonConfigSerializer(Config definition, Class<T> configClass) {
    }
}
