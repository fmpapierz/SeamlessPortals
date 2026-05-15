package com.warwa.seamlessportals.compat;

import com.warwa.seamlessportals.SeamlessPortalsConstants;

/**
 * Runtime detection + behavior switch for Sodium (the third-party chunk-
 * rendering optimization mod). Sodium replaces large parts of the vanilla
 * chunk-rendering pipeline that this mod hooks into — {@code SectionRenderDispatcher},
 * {@code ViewArea}, and most of {@code LevelRenderer.compileSections} are
 * effectively bypassed under Sodium.
 *
 * <p>Strategy: <b>if Sodium is not present, behave exactly as before</b>
 * (the existing vanilla-pipeline code path drives everything). <b>If
 * Sodium IS present, skip the parts of our pipeline that conflict with
 * Sodium and let Sodium drive chunk rendering for the cached secondary
 * renderers</b> the same way it drives the primary.
 *
 * <p>The detection is via {@link net.fabricmc.loader.api.FabricLoader#isModLoaded(String)}
 * on the {@code "sodium"} mod id (Sodium's id is stable across versions).
 * Result is cached once on first access — Sodium's presence doesn't
 * change at runtime.
 *
 * <p>We deliberately avoid compile-time dependencies on Sodium's API.
 * Anywhere we need to call into Sodium classes, we use reflection or
 * mixin {@code @Pseudo} targets so the mod still builds when the Sodium
 * source isn't on the classpath. The compatibility shim activates only
 * when Sodium is actually loaded.
 *
 * <p><b>Vanilla-mode behavior is the source of truth.</b> Anything new
 * in this class must be a guard around an existing vanilla-path call.
 * If {@link #isSodiumLoaded()} returns false, every call site must
 * behave identically to pre-compat code.
 */
public final class SodiumCompat {

    private SodiumCompat() {}

    /**
     * Cached result of the Sodium check. Lazy-initialized on first
     * {@link #isSodiumLoaded()} call (volatile + double-checked init is
     * unnecessary here — the flag is initialized once on the render
     * thread during early game init, and subsequent reads are fine on
     * any thread because the boolean assignment is atomic).
     */
    private static volatile Boolean cachedSodiumLoaded = null;

    /**
     * @return {@code true} iff Sodium is installed in the running game.
     *     Determined by querying {@code FabricLoader} for the
     *     {@code "sodium"} mod id. Cached on first call.
     */
    public static boolean isSodiumLoaded() {
        Boolean cached = cachedSodiumLoaded;
        if (cached != null) return cached;
        boolean detected = detectSodium();
        cachedSodiumLoaded = detected;
        if (detected) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS COMPAT] Sodium detected — vanilla-pipeline hooks will defer to Sodium's chunk renderer");
        } else {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS COMPAT] Sodium NOT detected — using vanilla pipeline");
        }
        return detected;
    }

    /**
     * Actual detection. Wrapped in a try/catch so a malformed loader
     * environment (e.g., a non-Fabric platform where FabricLoader isn't
     * on the classpath at all) returns false instead of throwing.
     *
     * <p>Reflected lookup keeps this class loadable on NeoForge too,
     * where {@code FabricLoader} is absent.
     */
    private static boolean detectSodium() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object result = loaderClass
                .getMethod("isModLoaded", String.class)
                .invoke(instance, "sodium");
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            // No FabricLoader (e.g., NeoForge platform) or Sodium has a
            // different id. Try NeoForge's ModList as a fallback.
            try {
                Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
                Object instance = modListClass.getMethod("get").invoke(null);
                Object result = modListClass
                    .getMethod("isLoaded", String.class)
                    .invoke(instance, "sodium");
                if (result instanceof Boolean && (Boolean) result) return true;
                // NeoForge port of Sodium is sometimes called "embeddium"
                // (rebranded fork). Check that too.
                result = modListClass
                    .getMethod("isLoaded", String.class)
                    .invoke(instance, "embeddium");
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }
}
