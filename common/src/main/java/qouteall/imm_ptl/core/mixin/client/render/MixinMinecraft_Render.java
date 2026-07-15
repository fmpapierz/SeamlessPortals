package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * S12-B (render client-mixin half) — VERBATIM IP {@code MixinMinecraft_Render}
 * ({@code IP:mixin/client/render/MixinMinecraft_Render.java}). PORTS-CLEAN (mixin-client.md §7):
 * {@code Minecraft.shouldEntityAppearGlowing(Entity)} survives unchanged ({@code 26.2:Minecraft.java:2657}).
 * Held/UNREGISTERED until S13.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_Render {
    // avoid render glowing entities when rendering portal
    @Inject(
        method = "shouldEntityAppearGlowing",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onShouldEntityAppearGlowing(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (WorldRenderInfo.isRendering()) {
            cir.setReturnValue(false);
        }
    }
}
