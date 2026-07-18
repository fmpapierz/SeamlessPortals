package com.warwa.seamlessportals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * The entity-portal migration MASTER SWITCH (D3, {@code migration/EXECUTION_PLAN.md} §1 D3;
 * {@code migration/EXCLUSIVITY_LEDGER.md}).
 *
 * <p>One boolean, {@code entityPortals}, keyed in the mod's own
 * {@code <configDir>/seamlessportals.properties} file (managed by {@link
 * com.warwa.seamlessportals.config.SeamlessPortalsConfig}). <b>Default {@code true} since S17
 * (THE CUTOVER FLIP, 2026-07-18)</b> — a fresh install runs the ported Immersive-Portals
 * entity-portal driver set. Set to {@code false} (and restart) to return to the block-era
 * portal driver set, byte-for-byte unchanged (the two-way switch survives until S20 deletes
 * the old system). Off Fabric the flag remains force-{@code false} (see below).
 *
 * <p><b>LOAD-TIME, read ONCE (D3).</b> The value is read a single time, lazily, and cached for the
 * whole JVM session. Flipping it requires a game restart — this is deliberate: it means the mixin
 * plugin ({@link com.warwa.seamlessportals.mixin.SeamlessMixinConfigPlugin}, which gates the IP
 * mixin set at bytecode-transform time, LONG before any mod initializer runs) and every runtime
 * {@code !entityPortals} gate in mod-owned driver code observe the SAME value, with no
 * half-initialized-manager hazard from a mid-session flip.
 *
 * <p><b>Why a standalone holder</b> (not a method on {@code SeamlessPortalsConfig}): this is invoked
 * from the mixin config plugin during Mixin bootstrap, the earliest code the mod runs. It touches
 * only {@code java.nio}/{@code java.util} + a reflective FabricLoader lookup — no Minecraft class,
 * no {@code SeamlessPortalsConfig} class-init chain — so it is safe to call at that stage. Every
 * failure path defaults to {@code false} (the safe block-era baseline).
 *
 * <p><b>Bootstrap ordering.</b> The plugin reads this before {@code SeamlessPortalsConfig.loadFrom}
 * runs, so on the very first launch (no properties file yet) this returns the DEFAULT ({@code true}
 * on Fabric since S17) and {@code loadFrom}/{@code saveTo} then write the key with that value. On
 * subsequent launches the user's edited value is read from the same file. Both readers hit the same
 * file/key, so they never disagree within a session.
 *
 * <p><b>Fabric-only hard gate (S13-B P4).</b> The ported IP integration is wired on Fabric ONLY (the
 * init sequence, the entity/renderer registrations, the mixin-config manifest entries — WIRE 1/2;
 * NeoForge IP integration is deferred, S07 §6). The whole IP mixin set references {@code net.fabricmc.*}
 * types that exist only as {@code compileOnly} stubs off the NeoForge runtime classpath, so if the flag
 * ever resolved {@code true} on NeoForge the {@code SeamlessMixinConfigPlugin} would weave those mixins
 * and boot would die with {@code NoClassDefFoundError}. Because {@link #resolveConfigDir()} falls back to
 * {@code ./config} when FabricLoader is absent, a stray {@code entityPortals=true} in a NeoForge config
 * WOULD otherwise trip that landmine. So the flag is force-{@code false} whenever FabricLoader is not on
 * the runtime classpath (i.e. on plain NeoForge). Fabric behaviour is unchanged in both flag states; only
 * NeoForge is pinned to the block-era baseline (which is all it ever ran anyway).
 *
 * <p>Deleted with the whole flag mechanism at S20.
 */
public final class EntityPortalsFlag {

    private static final String CONFIG_FILE_NAME = "seamlessportals.properties";
    static final String KEY = "entityPortals";

    private static volatile Boolean cached = null;

    private EntityPortalsFlag() {}

    /**
     * The load-time entity-portal master switch. Read once from
     * {@code <configDir>/seamlessportals.properties} (key {@code entityPortals}); cached for the
     * session. Since S17: missing dir/file/key defaults to {@code true} on Fabric; a hard
     * resolution ERROR (and any off-Fabric launch) still resolves {@code false}.
     */
    public static boolean isOn() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        boolean v = readFromDisk();
        cached = v;
        return v;
    }

    /**
     * Seed the cached value from an already-parsed config load ({@code SeamlessPortalsConfig.loadFrom}).
     * No-op if the value was already resolved (read-once semantics — the mixin plugin's earlier read
     * wins, and it read the same file, so this only matters on loaders where the plugin never ran).
     */
    public static synchronized void seedIfUnset(boolean value) {
        if (cached == null) {
            // S13-B P4: force-OFF off Fabric so a NeoForge config never turns the IP set on.
            // (S17 note: the seed value comes from an EXPLICIT config key — the flipped default
            // only applies in readFromDisk's missing-dir/file/key paths, identically here-vs-there
            // because loadFrom only calls this when the key is present.)
            cached = isFabricLoaderPresent() && value;
        }
    }

    private static boolean readFromDisk() {
        try {
            // S13-B P4: IP integration is Fabric-only. Off Fabric (FabricLoader absent → plain NeoForge)
            // the flag is force-OFF regardless of config, so the IP mixin set is never woven (it references
            // compileOnly net.fabricmc.* stubs) and the runtime gates all fall to the block-era path.
            if (!isFabricLoaderPresent()) {
                return false;
            }
            Path dir = resolveConfigDir();
            if (dir == null) {
                // S17 THE CUTOVER FLIP (EXECUTION_PLAN §S17, 2026-07-18): the DEFAULT is now TRUE
                // on Fabric — a fresh install (no config dir/file/key) runs the entity-portal
                // engine. An explicit entityPortals=false still returns to block portals (the
                // two-way switch survives until S20).
                return true;
            }
            Path file = dir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(file)) {
                return true; // S17 flip: fresh install → entity portals
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            String raw = props.getProperty(KEY);
            if (raw == null) {
                return true; // S17 flip: config exists but no key → the new default
            }
            return Boolean.parseBoolean(raw.trim());
        } catch (Throwable t) {
            // Any failure → block-era. A HARD FAILURE mid-resolution (I/O error on an existing
            // file) still falls to the proven baseline rather than half-resolving; the S17 flip
            // only applies on the clean missing-dir/file/key paths above. POST-FLIP this is a
            // visible RATCHET-DOWN (saveTo will persist false) — log it loudly so a transient
            // I/O blip pinning block-era is diagnosable, never silent (S17 verify minor).
            try {
                org.slf4j.LoggerFactory.getLogger("SeamlessPortals").warn(
                    "[SeamlessPortals] entityPortals flag resolution FAILED — falling back to the"
                        + " block-era baseline (false) for this session; if the config file is"
                        + " intact this may persist. Cause: {}", t.toString());
            } catch (Throwable ignored) {
                // never let logging break bootstrap
            }
            return false;
        }
    }

    /**
     * Whether FabricLoader is on the runtime classpath — i.e. we are running under Fabric (or a
     * Fabric-API-bridging environment like Sinytra Connector, where the {@code net.fabricmc.*} types the
     * IP set needs ARE actually present). Reflective so the earliest bootstrap code can call it without a
     * hard dependency; {@code false} on plain NeoForge (the {@code net.fabricmc.*} stubs are compileOnly,
     * off the runtime classpath). The gate that keeps flag-ON from tripping the NeoForge NoClassDefFoundError.
     */
    private static boolean isFabricLoaderPresent() {
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolve the mod config directory. Uses FabricLoader reflectively (the plugin runs before the
     * loader is fully wired, so a hard dependency is unwise), falling back to the conventional
     * {@code ./config} directory. Returns {@code null} only if even that cannot be formed.
     */
    private static Path resolveConfigDir() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object dir = loaderClass.getMethod("getConfigDir").invoke(instance);
            if (dir instanceof Path p) {
                return p;
            }
        } catch (Throwable ignored) {
            // not Fabric, or loader not ready — fall through to the default
        }
        try {
            return Paths.get("config");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
