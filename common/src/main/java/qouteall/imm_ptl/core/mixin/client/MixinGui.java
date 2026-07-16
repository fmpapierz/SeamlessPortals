package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_specific.IPConfig;

import java.util.List;
import java.util.function.Function;

/**
 * S12-B port disposition: NEEDS-RETARGET re-home of IP {@code MixinMinecraft}'s ⑦ handler
 * (mixin-client.md §1) — <b>ENABLED at S13-C</b> (was DEFERRED at S12-B pending its dependency).
 *
 * <p>IP 1.21.3 added the one-time ImmPtl config-help splash ({@code IPortalInitialScreen}) to the game's
 * initial-screen list at {@code Minecraft.addInitialScreens(List)} @RETURN (IP {@code MixinMinecraft.java:185-194}).
 * On 26.2 the anchor MOVED to {@code Gui} — {@code private boolean addInitialScreens(
 * List<Function<Runnable, Screen>> screens)} ({@code 26.2:Gui.java:381}), invoked from
 * {@code Gui.buildInitialScreens} ({@code :362}) which builds a fresh list at {@code :363}, calls
 * {@code addInitialScreens(screens)} at {@code :364}, then wraps every factory in
 * {@code Lists.reverse(screens)} ({@code :373}). Appending at @RETURN of {@code addInitialScreens} therefore
 * lands the splash into that same list before the wrap loop consumes it — behaviourally identical to IP.
 *
 * <p><b>26.2 signature deltas from IP.</b> (1) The method now returns {@code boolean} (onboarding-added flag),
 * so the callback is {@code CallbackInfoReturnable<Boolean>} rather than IP's {@code CallbackInfo}. (2) The
 * captured single argument is the {@code List<Function<Runnable, Screen>>} being populated — appended to
 * exactly as IP appended to {@code Minecraft}'s. The {@code IPortalInitialScreen(Runnable)} ctor supplies the
 * {@code Function<Runnable, Screen>} via {@code IPortalInitialScreen::new}. Append-if-not-shown gate
 * ({@code !IPConfig.getConfig().initialScreenShown}) is verbatim IP.
 *
 * <p><b>Why it was deferred at S12-B, now landed:</b> the referenced screen
 * {@code qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen} was outside the S12-B client-mixin slice;
 * it landed in the S13 closure set (U11 {@code miscellaneous/}), so the forward-ref is resolved and the
 * handler is re-enabled per zero-deviation. The config splash is non-load-bearing.
 */
@Mixin(Gui.class)
public class MixinGui {
    // ⑦ IP MixinMinecraft.onAddInitialScreens, re-homed onto Gui.addInitialScreens (see class javadoc).
    @Inject(method = "addInitialScreens", at = @At("RETURN"))
    private void onAddInitialScreens(
        List<Function<Runnable, Screen>> output,
        CallbackInfoReturnable<Boolean> cir
    ) {
        IPConfig config = IPConfig.getConfig();
        if (!config.initialScreenShown) {
            output.add(IPortalInitialScreen::new);
        }
    }
}
