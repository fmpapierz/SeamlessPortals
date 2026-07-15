package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B port disposition: NEEDS-RETARGET re-home of IP {@code MixinMinecraft}'s ⑦ handler
 * (mixin-client.md §1) — <b>DEFERRED</b>.
 *
 * <p>IP 1.21.3 added the one-time ImmPtl config-help splash ({@code IPortalInitialScreen}) to the game's
 * initial-screen list at {@code Minecraft.addInitialScreens(List)} @RETURN. On 26.2 the 26.2 anchor is
 * resolved — {@code addInitialScreens} moved to {@code Gui} ({@code private boolean addInitialScreens(
 * List<Function<Runnable, Screen>> screens)}, {@code 26.2:Gui.java:381}, invoked from
 * {@code Gui.buildInitialScreens} {@code :362} which reuses the same list) — so the port is:
 *
 * <pre>{@code
 * @Inject(method = "addInitialScreens", at = @At("RETURN"))
 * private void onAddInitialScreens(List<Function<Runnable, Screen>> output,
 *                                  CallbackInfoReturnable<Boolean> cir) {
 *     IPConfig config = IPConfig.getConfig();
 *     if (!config.initialScreenShown) { output.add(IPortalInitialScreen::new); }
 * }
 * }</pre>
 *
 * <p><b>Why deferred:</b> the screen it references, {@code qouteall.imm_ptl.core.miscellaneous
 * .IPortalInitialScreen}, is a {@code Screen} subclass NOT YET PORTED (a {@code miscellaneous} GUI class,
 * outside this client-mixin slice; its only qouteall dependency is the landed {@code IPConfig}, but its
 * vanilla GUI widgets — {@code ImageWidget}/{@code MultiLineTextWidget}/{@code HeaderAndFooterLayout}/
 * {@code LinearLayout} — carry 26.2 GUI-API retarget risk). Referencing it now would add a NON-S13-closure
 * forward-ref (HARD-GATE violation). The 26.2 anchor is fully worked out above; re-enable this handler when
 * the {@code miscellaneous} GUI cluster ({@code IPortalInitialScreen}) lands. The config splash is
 * non-load-bearing. Held/unregistered until S13.
 */
@Mixin(Gui.class)
public class MixinGui {
    // ⑦ addInitialScreens deferred pending IPortalInitialScreen (miscellaneous GUI). See class javadoc.
}
