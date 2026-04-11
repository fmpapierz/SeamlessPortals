package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.CameraTransitionHandler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRendererMixin - only handles camera transition ticking.
 * Portal rendering is now done via Fabric's LevelRenderEvents API.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void seamlessportals$beforeRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        CameraTransitionHandler.tick();
    }
}
