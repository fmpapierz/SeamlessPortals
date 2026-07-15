package qouteall.imm_ptl.core.mixin.client;

import com.mojang.blaze3d.opengl.GlDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §1). GL-debug diagnostic — prints a stack
 * trace for the first 100 GL debug messages (set {@code glDebugVerbosity:3} in options.txt).
 *
 * <p><b>26.2 retarget.</b> {@code GlDebug} moved package {@code com.mojang.blaze3d.platform} ->
 * {@code com.mojang.blaze3d.opengl} and {@code printDebugLog(IIIIIJJ)} became a <b>private INSTANCE</b>
 * method ({@code 26.2:opengl/GlDebug.java:71}, registered as a callback at {@code :119,:133}). Same
 * descriptor {@code (int,int,int,int,int,long,long)V}; the injected handler drops IP's {@code static}
 * because the target is now an instance method. GL-backend-only (silently inert under the Vulkan
 * backend). Held/unregistered until S13.
 */
@Mixin(GlDebug.class)
public class MixinGlDebug {
    private static int loggedNum = 0;

    @Inject(
        method = "Lcom/mojang/blaze3d/opengl/GlDebug;printDebugLog(IIIIIJJ)V", at = @At("RETURN")
    )
    private void onLogging(
        int source, int type, int id, int severity, int messageLength, long message, long l,
        CallbackInfo ci
    ) {
        if (loggedNum < 100) {
            new Throwable().printStackTrace();
            loggedNum++;
        }
    }
}
