package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.opengl.GlProgram;
import com.warwa.seamlessportals.render.ClipUniformLocationCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS5-H — PER-PROGRAM clip-location-cache invalidation at the UNIVERSAL deletion funnel
 * (2026-07-23; the acute form of the ledgered IS3 V4-3 stale-location residual).
 *
 * <p><b>The live incident:</b> an in-game shader-settings apply (the user re-enabling TAA) made
 * iris recompile ALL its programs via its own path — which NEVER calls
 * {@code GlDevice.clearPipelineCache}, the only invalidation seam the cache had. Recycled program
 * ids then resolved to STALE cached locations, and both clip uploaders fed {@code glUniform4f}
 * against locations the new programs never issued: ~248k {@code GL_INVALID_OPERATION} lines
 * ("operation is invalid when the uniform is a matrix") + real wrong-uniform writes corrupting
 * live iris programs for the rest of the session. The original ledger rationale ("iris programs
 * bind outside the vanilla trySetup path this cache serves") was refuted by probe v1 — iris
 * terrain {@code ExtendedShader}s provably bind at {@code trySetup}.
 *
 * <p><b>The seam:</b> {@code GlProgram.close()} — javap-confirmed the single funnel: vanilla
 * {@code clearPipelineCache} closes its cached {@code GlProgram}s through it, and iris's
 * {@code ExtendedShader extends GlProgram} inherits {@code close()} un-overridden, so every iris
 * recompile path (settings apply, pack switch, reload) deletes through here too. HEAD injection:
 * the id is read before the GL name dies. Per-id removal — no collateral invalidation of live
 * programs (the {@code GlDevice} bulk-clear mixin stays as a harmless belt).
 */
@Mixin(GlProgram.class)
public abstract class GlProgramClipCacheMixin {

    @Inject(method = "close", at = @At("HEAD"), require = 1)
    private void seamlessportals$invalidateClipLocationOnClose(CallbackInfo ci) {
        ClipUniformLocationCache.remove(((GlProgram) (Object) this).getProgramId());
    }
}
