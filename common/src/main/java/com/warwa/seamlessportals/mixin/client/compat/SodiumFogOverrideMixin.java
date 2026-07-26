package com.warwa.seamlessportals.mixin.client.compat;

import com.warwa.seamlessportals.render.SodiumFogOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Override Sodium's captured {@code FogParameters} during portal rendering
 * so the destination dim's fog is used for chunk-mesh draws inside the
 * FBO, and the source dim's fog is preserved for the main world.
 *
 * <p>Sodium's GameRendererMixin captures fog when vanilla writes the
 * projection-matrix buffer (via a wrap-operation on
 * {@code ProjectionMatrixBuffer.set}). The captured value is exposed via
 * {@code GameRendererStorage.sodium$getFogParameters()} and used by
 * {@code SodiumWorldRenderer.setupTerrain} / chunk draws. Our portal
 * render path uses a separate fog buffer and never goes through Sodium's
 * capture point, so without this mixin Sodium serves the main-render's
 * captured fog into the portal FBO draw.
 *
 * <p>The mixin targets Sodium's interface method directly — when
 * {@link SodiumFogOverride#hasActiveOverride()} is true (set by
 * {@link com.warwa.seamlessportals.render.PortalContextSwitch} for the
 * duration of the portal-view render), the getter returns our override
 * instead of Sodium's captured value.
 *
 * <p>{@code @Pseudo} because the target class only exists at runtime
 * when Sodium is installed. Mixin gracefully no-ops the mixin when the
 * target is absent (no compile dep on Sodium).
 */
@Pseudo
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class SodiumFogOverrideMixin {

    // C2-0 probe P8 (migration/C2_DESIGN.md §4 P8): one-shot latch so the lever-gated hit log below
    // fires at most once per session. Zero effect without -Dseamlessportals.compatProbe=true.
    // Lever cached in a static final like the sibling probes (JIT folds the lever-off branch away).
    private static final boolean seamlessportals$probe = Boolean.getBoolean("seamlessportals.compatProbe");
    private static volatile boolean seamlessportals$probeP8Logged = false;

    /**
     * Sodium's {@code GameRendererMixin} merges a method named
     * {@code sodium$getFogParameters} into {@link net.minecraft.client.renderer.GameRenderer}
     * (declared on the interface {@code GameRendererStorage} that the mixin
     * makes GameRenderer implement). We inject into THAT merged method.
     *
     * <p>{@code require = 0} so this mixin no-ops cleanly when Sodium is
     * absent (the merged method doesn't exist). {@code remap = false}
     * because the method name is mod-specific, not a vanilla Mojang/Yarn
     * identifier.
     */
    @Inject(method = "sodium$getFogParameters",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void seamlessportals$overrideFog(CallbackInfoReturnable<Object> cir) {
        Object override = SodiumFogOverride.currentOverride();
        // C2-0 probe P8 (lever-gated, one-shot): record that this getter fired under Sodium.
        // S20 increment 4: the `entityPortals={}` argument is gone with the flag — it was a LOG
        // ARGUMENT, not an `if`, so a mechanical "collapse every gate" pass misses it and leaves a
        // compile error behind (port-note §G.12). The pair itself is flag-ON load-bearing and stays
        // (§0.2): SecondaryWorldRenderCore arms the override for the dest extract's duration.
        if (seamlessportals$probe && !seamlessportals$probeP8Logged) {
            seamlessportals$probeP8Logged = true;
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[COMPAT PROBE P8] SodiumFogOverrideMixin.sodium$getFogParameters fired; activeOverride={}",
                override != null);
        }
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
