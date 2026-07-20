package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.util.FlawlessFrames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;

/**
 * C2-1 — IP depth doc file #9, PORT-AS-IS (1:1). javap: static {@code isActive()Z} on
 * {@code net.caffeinemc.mods.sodium.client.util.FlawlessFrames} EXISTS-IDENTICAL on 0.9.1
 * (census CONFIRMATIONS :159).
 *
 * <p>When IP has armed a forced main-thread rebuild for the current frame, Sodium is made to
 * believe the Fabric flawless-frames (recording) API is active — which raises setupTerrain's
 * build-drain loop cap from 1 to renderDistance iterations AND forces finalizeRenderLists down
 * the synchronous renderOutOfGraph path (census (e), P10) — so the needed chunks mesh THIS frame
 * instead of popping in over several (the post-crossing "blank curtain" class). Armed by
 * {@code ForceMainThreadRebuild.forceMainThreadRebuildFor(n)}; C2-1 arms n=1 on cold registered
 * portal-context creation in {@code SodiumContextRegistry.acquire}. IP's own honesty note rides
 * along: "this is sometimes effective but not always effective".
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = FlawlessFrames.class, remap = false)
public abstract class MixinSodiumFlawlessFrames {

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ip_onIsActive(CallbackInfoReturnable<Boolean> cir) {
        if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) {
            cir.setReturnValue(true);
        }
    }
}
