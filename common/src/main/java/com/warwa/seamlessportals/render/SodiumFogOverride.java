package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.renderer.fog.FogData;

import java.lang.reflect.Constructor;

/**
 * Render-thread holder for an override to Sodium's
 * {@code FogParameters} during portal-view rendering. Set by
 * {@link PortalContextSwitch} before invoking
 * {@code destRenderer.renderLevel(...)}, cleared in the {@code finally}.
 *
 * <p>The actual override is consumed by
 * {@link com.warwa.seamlessportals.mixin.client.compat.SodiumFogOverrideMixin}
 * via {@link #currentOverride()}. The mixin returns this value from
 * Sodium's {@code GameRendererStorage.sodium$getFogParameters} when the
 * override is active, so Sodium's chunk-draw uses the destination dim's
 * fog instead of the source dim's main-render captured fog.
 *
 * <p>We construct Sodium's {@code FogParameters} via reflection from the
 * vanilla {@link FogData} so we avoid a compile-time dependency on
 * Sodium. If the construction fails (Sodium absent or class moved), the
 * override stays null and the mixin no-ops, which means Sodium serves
 * its captured main-render fog into the portal view — visible glitch
 * but not a crash.
 *
 * <p>Render-thread-only — Sodium and we both run on the render thread,
 * so a simple non-thread-safe field is fine.
 */
public final class SodiumFogOverride {

    private SodiumFogOverride() {}

    private static Object current = null;
    private static volatile boolean ctorResolved = false;
    private static volatile Constructor<?> fogParametersCtor;

    /**
     * Resolve Sodium's {@code FogParameters(float×8)} constructor once
     * and cache it. Falls back to null if Sodium isn't on the classpath
     * — callers no-op cleanly.
     */
    private static Constructor<?> getFogParametersCtor() {
        if (ctorResolved) return fogParametersCtor;
        synchronized (SodiumFogOverride.class) {
            if (ctorResolved) return fogParametersCtor;
            try {
                Class<?> cls = Class.forName(
                    "net.caffeinemc.mods.sodium.client.util.FogParameters");
                fogParametersCtor = cls.getConstructor(
                    float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class);
            } catch (Throwable t) {
                fogParametersCtor = null;
            }
            ctorResolved = true;
            return fogParametersCtor;
        }
    }

    /**
     * Activate an override built from the given destination-dim {@link FogData}.
     * The override remains active until {@link #clear()} is called — paired
     * try/finally usage required.
     *
     * <p>Field mapping (vanilla {@link FogData} → Sodium {@code FogParameters}):
     * <ul>
     *   <li>{@code red/green/blue/alpha} ← {@code fogData.color} (Vector4f)</li>
     *   <li>{@code environmentalStart/End} ← matching fields on {@link FogData}</li>
     *   <li>{@code renderStart/End} ← {@code renderDistanceStart/End}</li>
     * </ul>
     */
    public static void activate(FogData destFog) {
        if (destFog == null) return;
        Constructor<?> ctor = getFogParametersCtor();
        if (ctor == null) return;
        try {
            current = ctor.newInstance(
                destFog.color.x, destFog.color.y, destFog.color.z, destFog.color.w,
                destFog.environmentalStart, destFog.environmentalEnd,
                destFog.renderDistanceStart, destFog.renderDistanceEnd);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SODIUM] FogOverride activate failed: {}", t.toString());
            current = null;
        }
    }

    /** Drop the override. Called in {@code finally} blocks after portal render. */
    public static void clear() {
        current = null;
    }

    /**
     * Used by {@link com.warwa.seamlessportals.mixin.client.compat.SodiumFogOverrideMixin}
     * to fetch the active override. Returns {@code null} when no override
     * is active — the mixin passes through to Sodium's captured value in
     * that case.
     */
    public static Object currentOverride() {
        return current;
    }

    /** True iff an override is currently active. */
    public static boolean hasActiveOverride() {
        return current != null;
    }
}
