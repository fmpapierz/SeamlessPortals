package qouteall.imm_ptl.core.mixin.client.interaction;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §4). Cross-portal block targeting: suppresses
 * the pick raycast during portal-view rendering, and computes the through-portal pointed block after the
 * vanilla pick.
 *
 * <p><b>26.2 retarget (file kept at IP's verbatim held path {@code interaction/MixinGameRenderer_B}, target
 * moved).</b> IP 1.21.3 hooked {@code GameRenderer.pick(F)}. On 26.2 {@code pick} moved to
 * {@code Minecraft} — {@code private void pick(float)} ({@code 26.2:Minecraft.java:2934}, called per-frame
 * {@code :1292} and per-tick {@code :1775}). Both handlers re-target onto {@code Minecraft.pick} (same
 * cancel/append semantics); the {@code @Mixin} target therefore changes {@code GameRenderer} ->
 * {@code Minecraft}. {@code BlockManipulationClient} is the S13 block_manipulation closure set (forward-ref
 * resolves at S13). Held/unregistered until S13.
 */
@Mixin(Minecraft.class)
public class MixinGameRenderer_B {

    //do not update target when rendering portal
    @Inject(method = "Lnet/minecraft/client/Minecraft;pick(F)V", at = @At("HEAD"), cancellable = true)
    private void onUpdateTargetedEntity(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            if (WorldRenderInfo.isRendering()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "Lnet/minecraft/client/Minecraft;pick(F)V", at = @At("RETURN"))
    private void onUpdateTargetedEntityFinish(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            BlockManipulationClient.updatePointedBlock(partialTick);
        }
    }
}
