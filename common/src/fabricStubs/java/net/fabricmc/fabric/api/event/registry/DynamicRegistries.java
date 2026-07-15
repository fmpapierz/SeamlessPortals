// S13 loader-seam compileOnly stub (entity-portal migration; port-note S13A-u11.md). NOT IP source,
// NOT shipped. The held tree registers its two datapack registries (custom portal generation +
// legacy) through DynamicRegistries.register(...) in CustomPortalGenManager.init (:44,:48). RUNTIME
// registration that wires the REAL fabric-registry-sync type at S13 (:common compile classpath only
// — the real fabric-api type resolves at the fabric loader compile; no shadow, per S10A §6).
// Signature is a verbatim copy of the real fabric-registry-sync-v0 DynamicRegistries.register(...)
// surface the held tree consumes (the single two-arg register only). Removed at S20.
package net.fabricmc.fabric.api.event.registry;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

public final class DynamicRegistries {
    public static <T> void register(ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
    }

    private DynamicRegistries() {
    }
}
