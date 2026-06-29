package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.warwa.seamlessportals.render.FrontClipping;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Upload the {@code seamlessportals_ClipPlane} vec4 to every shader
 * program that declares it, in the per-draw pipeline-setup path.
 *
 * <p>Locations are cached per program id — first-use query only.
 * Programs that don't have the uniform get location == -1 once and we
 * remember that as "not applicable". Targets package-private
 * {@code GlCommandEncoder} via string {@code targets=}.
 */
@Mixin(targets = "com/mojang/blaze3d/opengl/GlCommandEncoder")
public abstract class GlCommandEncoderClipMixin {

    /** Cache: programId → uniform location (or -1 if absent). */
    private static final Map<Integer, Integer> seamlessportals$locationCache = new HashMap<>();

    @Inject(
        method = "trySetup(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z",
        at = @At("RETURN"),
        require = 1
    )
    private void seamlessportals$uploadClipPlaneAtReturn(CallbackInfoReturnable<Boolean> cir) {
        // Only when trySetup succeeded — false returns mean no draw will happen.
        if (Boolean.FALSE.equals(cir.getReturnValue())) return;
        int programId = org.lwjgl.opengl.GL20.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        seamlessportals$uploadForProgram(programId);
    }

    private static void seamlessportals$uploadForProgram(int programId) {
        if (programId <= 0) return;

        Integer cached = seamlessportals$locationCache.get(programId);
        int loc;
        if (cached == null) {
            loc = GlStateManager._glGetUniformLocation(programId, ShaderCodeTransformation.UNIFORM_NAME);
            seamlessportals$locationCache.put(programId, loc);
        } else {
            loc = cached;
        }
        if (loc < 0) return;

        float px = FrontClipping.getPlaneX();
        float py = FrontClipping.getPlaneY();
        float pz = FrontClipping.getPlaneZ();
        float pw = FrontClipping.getPlaneW();
        GL20.glUniform4f(loc, px, py, pz, pw);

        // GL_CLIP_DISTANCE0 enable/disable is managed by FrontClipping
        // (enableGlClipDistance / disableGlClipDistance). When plane is
        // the default (0,0,0,1), gl_ClipDistance = +1 constant so even
        // if the GL capability is enabled, nothing is clipped — the
        // uniform upload is a no-op semantically. No per-draw enable
        // needed (matches IP's shader-bind-time hook behavior).
    }
}
