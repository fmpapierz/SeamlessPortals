package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.ClipUniformLocationCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS2 UNCONDITIONAL HARDENING (iris shaders-ON engagement; port-note
 * IS-iris-shaders-on §3.2): invalidate the clip-plane uniform-location cache
 * whenever the GL backend bulk-deletes its compiled programs.
 *
 * <p>{@code GlDevice.clearPipelineCache} (mc262 {@code GlDevice:261}; called
 * by {@code ShaderManager:152/:162} on every resource reload — F3+T, pack
 * apply — and by {@code GlDevice.close}) closes every cached
 * {@code GlProgram} via {@code glDeleteProgram}. Every entry of
 * {@link ClipUniformLocationCache} is stale from that point on: new programs
 * can reuse the deleted ids with the uniform at different locations, turning
 * the cached values into wrong-location {@code glUniform4f} feeds
 * ({@code GL_INVALID_OPERATION} spam + a wrong-uniform-write hazard). RETURN
 * injection: the clear happens after the deletions are complete, so a throw
 * mid-clear cannot leave us cleared-but-programs-alive (harmless either way —
 * a spurious clear only re-queries locations).
 *
 * <p>Rationale for the seam choice (vs the spec's fallback options) is
 * documented on {@link ClipUniformLocationCache}.
 *
 * <p>Targets package-private {@code GlDevice} via string {@code targets=}
 * (like the sibling {@link GlCommandEncoderClipMixin}) rather than a
 * {@code @Mixin(GlDevice.class)} class literal, so this mixin's compile does
 * not silently depend on the S14.21 FBO-resolver access-widener line that
 * happens to promote {@code GlDevice} to public (IS2 fold V4-1).
 */
@Mixin(targets = "com/mojang/blaze3d/opengl/GlDevice")
public abstract class GlDeviceClipCacheMixin {

    @Inject(method = "clearPipelineCache", at = @At("RETURN"), require = 1)
    private void seamlessportals$invalidateClipLocationCache(CallbackInfo ci) {
        ClipUniformLocationCache.clear();
    }
}
