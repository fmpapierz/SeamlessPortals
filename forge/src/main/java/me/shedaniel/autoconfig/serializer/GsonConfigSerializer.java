// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ../ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig.serializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The GSON-backed serializer IP references as {@code GsonConfigSerializer::new}, which binds as a
 * {@link ConfigSerializer.Factory} — hence the {@code (Config, Class<T>)} constructor. It reads/writes
 * {@code <configDir>/<name>.json} where {@code name} comes from the {@link Config} annotation, matching
 * Cloth-Config's file-naming (IPConfig → {@code config/immersive_portals.json}).
 *
 * <p>The config directory is resolved reflectively via FabricLoader (falling back to {@code ./config})
 * — the same self-contained lookup {@code EntityPortalsFlag} uses — so this package carries no compile
 * or runtime dependency on the loader facade. All I/O is best-effort: a missing/corrupt file yields a
 * fresh default, and a failed write is swallowed, so config resolution never throws at boot.
 */
public class GsonConfigSerializer<T extends ConfigData> implements ConfigSerializer<T> {
    private final Config definition;
    private final Class<T> configClass;
    private final Gson gson;

    public GsonConfigSerializer(Config definition, Class<T> configClass) {
        this.definition = definition;
        this.configClass = configClass;
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    @Override
    public T createDefault() {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + configClass.getName(), e);
        }
    }

    @Override
    public T deserialize() {
        Path path = getConfigPath();
        if (path == null || !Files.exists(path)) {
            return createDefault();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T loaded = gson.fromJson(reader, configClass);
            return loaded != null ? loaded : createDefault();
        }
        catch (Throwable e) {
            // corrupt / unreadable config → defaults; never destabilize load
            return createDefault();
        }
    }

    @Override
    public void serialize(T config) {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(config, writer);
            }
        }
        catch (Throwable ignored) {
            // best-effort persist; never throw
        }
    }

    private Path getConfigPath() {
        Path dir = resolveConfigDir();
        if (dir == null) {
            return null;
        }
        String name = (definition != null) ? definition.name() : configClass.getSimpleName();
        return dir.resolve(name + ".json");
    }

    private static Path resolveConfigDir() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object dir = loaderClass.getMethod("getConfigDir").invoke(instance);
            if (dir instanceof Path p) {
                return p;
            }
        }
        catch (Throwable ignored) {
            // not Fabric, or loader not ready — fall through to the default
        }
        try {
            return Paths.get("config");
        }
        catch (Throwable ignored) {
            return null;
        }
    }
}
