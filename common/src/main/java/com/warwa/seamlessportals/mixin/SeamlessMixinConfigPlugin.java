package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.EntityPortalsFlag;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for compile-time mixin gating.
 *
 * <p>Some of our mixins target vanilla methods that Sodium overwrites at
 * the same priority (e.g. {@code LevelRenderer.cullTerrain}). Mixin
 * raises an {@code InvalidInjectionException} on these conflicts before
 * {@code require = 0} can take effect, so we have to filter them out at
 * the config-plugin level — Mixin asks {@link #shouldApplyMixin(String, String)}
 * for each candidate mixin and skips the ones we return {@code false} for.
 *
 * <p>The runtime detection here is hand-rolled (not {@link com.warwa.seamlessportals.compat.SodiumCompat})
 * because the config plugin loads VERY early — before FabricLoader is
 * fully wired in some bootstrap orderings. We use a try/reflective
 * lookup with FabricLoader and ModList as fallbacks, identical in spirit
 * to {@code SodiumCompat#detectSodium}.
 *
 * <p>The set in {@link #SODIUM_INCOMPATIBLE_MIXINS} lists fully-qualified
 * class names of mixins to omit when Sodium is present. Add to it as new
 * conflicts are discovered.
 */
public class SeamlessMixinConfigPlugin implements IMixinConfigPlugin {

    /**
     * Mixin class names (matching the {@code package + "." + classname}
     * Mixin uses in {@link #shouldApplyMixin}) that conflict with Sodium
     * and must be skipped when Sodium is present.
     *
     * <p>The package prefix here is set by {@code "package"} in the
     * mixin config file. For {@code seamlessportals-common.mixins.json}
     * that's {@code com.warwa.seamlessportals.mixin}, so the entries
     * here are listed WITHOUT that prefix.
     */
    private static final Set<String> SODIUM_INCOMPATIBLE_MIXINS = Set.of(
        "com.warwa.seamlessportals.mixin.client.LevelRendererCullTerrainMixin"
    );

    /**
     * WEAVE-LEVEL EXCLUSIVITY (D3 extension, S13 first-light attempt 3): block-era mixins that
     * COLLIDE at the bytecode level with a registered IP mixin on the same injection site
     * (two {@code @Redirect}s on one instruction = Mixin skips the second and its
     * {@code require} check kills the boot). Runtime {@code !entityPortals} gates cannot help
     * here — the collision happens at transform time. These are skipped when the flag is ON;
     * their function is superseded by the IP counterpart (each entry documents by what).
     */
    private static final Set<String> ENTITY_PORTALS_SUPERSEDED_MIXINS = Set.of(
        // superseded by qouteall...client.sync.MixinClientPacketListener redirectGetEntityById
        // (IP resolves entities across per-dim client worlds — strict superset of the
        // local-player fallback; see the block-era mixin's own IP-parity javadoc note)
        "com.warwa.seamlessportals.mixin.client.ClientPacketListenerLocalPlayerFallbackMixin"
    );

    private static volatile Boolean sodiumLoaded = null;

    private static boolean isSodiumPresent() {
        Boolean cached = sodiumLoaded;
        if (cached != null) return cached;
        boolean detected = detect();
        sodiumLoaded = detected;
        return detected;
    }

    private static boolean detect() {
        // FabricLoader path
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object result = loaderClass
                .getMethod("isModLoaded", String.class)
                .invoke(instance, "sodium");
            if (result instanceof Boolean && (Boolean) result) return true;
        } catch (Throwable ignored) {
            // not Fabric or class missing
        }
        // NeoForge path
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Object instance = modListClass.getMethod("get").invoke(null);
            Object result = modListClass
                .getMethod("isLoaded", String.class)
                .invoke(instance, "sodium");
            if (result instanceof Boolean && (Boolean) result) return true;
            result = modListClass
                .getMethod("isLoaded", String.class)
                .invoke(instance, "embeddium");
            if (result instanceof Boolean && (Boolean) result) return true;
        } catch (Throwable ignored) {
            // not NeoForge or no Sodium fork present
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // No-op — we read from the static SODIUM_INCOMPATIBLE_MIXINS set.
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // D3 EXCLUSIVITY GATE (entity-portal migration, migration/EXCLUSIVITY_LEDGER.md §4):
        // the ported Immersive-Portals mixin set lives in the qouteall.* packages. It is woven
        // ONLY when the entity-portal engine is ON. Flag OFF (the shipping default) → every IP
        // mixin is skipped here, so the block-era com.warwa mixins are the only portal driver set
        // applied. This is the load-time half of the one-driver-per-session contract; the runtime
        // half is the `!entityPortals` gates in the block-era mod driver code.
        if (mixinClassName != null && mixinClassName.startsWith("qouteall.")) {
            if (!EntityPortalsFlag.isOn()) {
                return false;
            }
        }
        // The mirror half: flag ON suppresses block-era mixins whose injection sites collide
        // with a registered IP mixin (weave-level exclusivity; see the set's javadoc).
        if (EntityPortalsFlag.isOn() && ENTITY_PORTALS_SUPERSEDED_MIXINS.contains(mixinClassName)) {
            System.out.println("[SEAMLESS EXCLUSIVITY] Skipping block-era mixin " + mixinClassName
                + " (superseded by the IP set while entityPortals is ON)");
            return false;
        }
        if (isSodiumPresent() && SODIUM_INCOMPATIBLE_MIXINS.contains(mixinClassName)) {
            System.out.println("[SEAMLESS COMPAT] Skipping mixin " + mixinClassName
                + " (incompatible with Sodium)");
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // No-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // No-op
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        // No-op
    }
}
