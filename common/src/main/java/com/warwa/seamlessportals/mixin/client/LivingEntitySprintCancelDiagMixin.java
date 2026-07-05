package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: pinpoints WHO cancels the local player's sprint right after a
 * crossing. The XTRACE speed channel proved the sprint speed modifier drops on
 * the first post-swap tick, but none of the vanilla stop conditions obviously
 * apply — so this logs the calling code path (3 stack frames) of any
 * sprint-cancel on the local player inside the post-swap window, into the
 * crossing tracer's event stream. The behavioural fix is the sprint keeper in
 * {@code SeamlessClientTeleport} (re-asserts sprint at tick end); this mixin is
 * the 100%-certainty evidence channel and costs nothing outside the window.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySprintCancelDiagMixin {

    @Inject(method = "setSprinting", at = @At("HEAD"))
    private void seamlessportals$diagSprintCancel(boolean sprinting, CallbackInfo ci) {
        if (sprinting) return;
        Object self = this;
        if (!(self instanceof LocalPlayer player)) return;
        if (!player.isSprinting()) return;               // no change → not a cancel
        if (!SeamlessClientTeleport.isInPostSwapWindow()) return;

        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < Math.min(4, stack.length); i++) {
            if (path.length() > 0) path.append(" <- ");
            path.append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
        }
        // Condition snapshot: which shouldStopRunSprinting sub-condition fired?
        // (hColl = horizontalCollision [&& !minor = the cancel trigger], fwd =
        // input forward impulse, water/ground/food = isSprintingPossible inputs.)
        com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
            "SPRINT CANCELLED post-swap via %s [hColl=%b minor=%b fwd=%b ground=%b water=%b food=%d]",
            path,
            player.horizontalCollision,
            player.minorHorizontalCollision,
            player.input != null && player.input.hasForwardImpulse(),
            player.onGround(),
            player.isInWater(),
            player.getFoodData().getFoodLevel()));
    }
}
