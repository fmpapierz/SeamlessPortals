package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "close", at = @At("HEAD"))
    private void seamlessportals$onClose(CallbackInfo ci) {
        StencilPortalRenderer.cleanup();
    }
}
