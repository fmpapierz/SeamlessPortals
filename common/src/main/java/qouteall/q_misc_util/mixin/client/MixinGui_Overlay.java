package qouteall.q_misc_util.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import qouteall.q_misc_util.CustomTextOverlay;

// 26.2 render-model re-target (migration/fragments/S07-qmisc.md §MixinGui_Overlay): IP injects at
// Gui.render(GuiGraphics, float) RETURN, but 26.2 replaced that with the extract model —
// Gui.extractRenderState(DeltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded) which
// builds a local GuiGraphicsExtractor and hands it to the HUD/overlay/screen extractors. This
// re-target injects at that method's RETURN and captures the local GuiGraphicsExtractor via the
// classic Sponge LocalCapture (MixinExtras @Local is not on the common compile classpath at S7).
// The `shouldRenderLevel` flag stands in for IP's `!minecraft.options.hideGui` gate (26.2 relocated
// the hideGui state off Options). HELD + unregistered until S13; the exact injection point, the
// captured-local order (FAILHARD), and the gate are render-family items to be verified at S11/S12
// against the render api-map.
@Mixin(Gui.class)
public class MixinGui_Overlay {
    @Inject(
        method = "extractRenderState",
        at = @At("RETURN"),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onRender(
        DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo ci,
        ProfilerFiller profiler, int xMouse, int yMouse, GuiGraphicsExtractor graphics
    ) {
        if (shouldRenderLevel) {
            CustomTextOverlay.render(graphics, deltaTracker);
        }
    }
}
