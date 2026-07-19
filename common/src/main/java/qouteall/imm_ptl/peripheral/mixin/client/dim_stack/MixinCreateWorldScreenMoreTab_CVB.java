package qouteall.imm_ptl.peripheral.mixin.client.dim_stack;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.ducks.IECreateWorldScreen;

/**
 * S19-C port of IP:peripheral/mixin/client/dim_stack/MixinCreateWorldScreenMoreTab_CVB —
 * adds the full-width "Dimension Stack" button (lang key imm_ptl.altius_screen_button,
 * width 210, IP-exact) to the create-world screen's More tab. 26.2 adaptations (pinned):
 * (1) the outer-ref synthetic is {@code this$0} — verified in BOTH the merged-deobf jar AND
 * the RAW Mojang 26.2 jar (verify wf_b94c1d5e-5f8: 26.2 ships UNOBFUSCATED, so the dev name
 * IS the production name; no remap involved — this repo has no remap machinery, and IP's
 * 1.21.3 field_42178 intermediary indirection has no 26.2 analogue); (2) IP's
 * {@code LocalCapture.CAPTURE_FAILHARD} of the local {@code GridLayout.RowHelper} becomes
 * MixinExtras {@code @Local} (the repo's S10-C precedent — LVT-shape-robust). The 26.2
 * MoreTab ctor keeps the same local-RowHelper + addChild(Button.builder...width(210))
 * shape (CreateWorldScreen.java:709-729).
 */
@Mixin(CreateWorldScreen.MoreTab.class)
public class MixinCreateWorldScreenMoreTab_CVB {
    @SuppressWarnings("ShadowTarget")
    @Final
    @Shadow
    CreateWorldScreen this$0;

    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onInitEnd(CallbackInfo ci, @Local GridLayout.RowHelper rowHelper) {
        rowHelper.addChild(
            Button.builder(
                    Component.translatable("imm_ptl.altius_screen_button"),
                    b -> ((IECreateWorldScreen) this$0).ip_openDimStackScreen()
                )
                .width(210)
                .build()
        );
    }
}
