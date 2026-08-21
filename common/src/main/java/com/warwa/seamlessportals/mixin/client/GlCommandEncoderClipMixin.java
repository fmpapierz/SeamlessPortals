package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.warwa.seamlessportals.render.ClipDiscriminatorProbe;
import com.warwa.seamlessportals.render.ClipUniformLocationCache;
import com.warwa.seamlessportals.render.FrontClipping;
import com.warwa.seamlessportals.render.FullPipelineClipState;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Upload the {@code seamlessportals_ClipPlane} vec4 to every shader
 * program that declares it, in the per-draw pipeline-setup path.
 *
 * <p>Locations are cached per program id — first-use query only.
 * Programs that don't have the uniform get location == -1 once and we
 * remember that as "not applicable". Targets package-private
 * {@code GlCommandEncoder} via string {@code targets=}.
 *
 * <p>IS2 UNCONDITIONAL HARDENING: the cache moved to
 * {@link ClipUniformLocationCache} (a plain holder both mixins can reach)
 * and is invalidated by {@link GlDeviceClipCacheMixin} whenever
 * {@code GlDevice.clearPipelineCache} deletes the cached programs —
 * without that, id reuse after F3+T / pack applies feeds stale locations
 * to {@code glUniform4f} (GL_INVALID_OPERATION spam + wrong-uniform-write
 * hazard). Rationale + seam evidence on the holder's javadoc.
 *
 * <p>IS3 §4.0 PER-DRAW DEFINEDNESS GUARD: the upload body gained a per-draw
 * {@code GL_CLIP_DISTANCE0} enable-authority guard (mirror of the sodium
 * uploader's) so that under the shaders-ON full-pipeline dest render — which
 * arms a GLOBAL clip for the whole nested {@code render()} — iris's
 * un-injected NON-terrain programs (entities/sky/particles that write no
 * {@code gl_ClipDistance}) draw DEFINED-and-unclipped instead of hitting the
 * GL "enabled-but-not-written = undefined" rule. Keyed on the store's armed
 * intent ({@code FrontClipping.capture().enabled}) OR the pass-scoped full-
 * pipeline override ({@link com.warwa.seamlessportals.render.FullPipelineClipState},
 * IS3 V6 fold — see below), raw per-draw toggle.
 *
 * <p>IS3 V6 FOLD (the M4 reconciliation): the belt-swap arm in
 * {@code SecondaryWorldRenderCore.renderDestWorldFullPipeline} is disarmed
 * mid-pass by M4 ({@code MixinLevelRenderer_CrossPortalEntity} submitEntities
 * TAIL → {@code disableClipping()}) during {@code render()}'s {@code submitFeatures}
 * BUILD phase, BEFORE the framegraph terrain execute — so {@code capture().enabled}
 * reads {@code false} (and the live plane reads keep-all) at terrain-draw time.
 * This uploader therefore consults {@code FullPipelineClipState} as an alternate
 * arm source + plane: when it is armed the terrain enable re-asserts from the
 * FROZEN belt plane regardless of the live store. Off the full-pipeline path the
 * override is never armed, so the arm decision reduces to {@code capture().enabled}
 * and the live-store plane — unchanged.
 *
 * <p>SHADERS-OFF disposition (IS3 V1 correction — NOT literal instruction-identity):
 * {@code ShaderCodeTransformation} is a CONSERVATIVE injector (its own javadoc names
 * {@code rendertype_end_portal} / panorama / ungated programs as left un-injected,
 * loc == -1), so an un-injected world program CAN draw inside an armed decomposed
 * inner-clip window and now hits the new {@code glDisable} suppress branch where
 * pre-IS3 the handler did nothing. That is a defined-and-unclipped SAFE improvement
 * (undefined → defined; same pixels on NVIDIA, correct on AMD/Intel), NOT a
 * regression — but it is a real GL-instruction delta, so the earlier "bit-identical
 * / suppress never fires" claim is retired. With the LEVER OFF the full-pipeline
 * renderer never runs, the override stays disarmed, and this handler reads exactly
 * {@code capture().enabled} — GL-state identical to the plain-sodium path.
 */
@Mixin(targets = "com/mojang/blaze3d/opengl/GlCommandEncoder")
public abstract class GlCommandEncoderClipMixin {

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

        Integer cached = ClipUniformLocationCache.get(programId);
        int loc;
        if (cached == null) {
            loc = GlStateManager._glGetUniformLocation(programId, ShaderCodeTransformation.UNIFORM_NAME);
            ClipUniformLocationCache.put(programId, loc);
        } else {
            loc = cached;
        }

        // IS3 §4.0 THE PER-DRAW DEFINEDNESS GUARD (mirror of the sodium uploader's
        // MixinSodiumGLDrawContext_ClipUpload guard, ported to this universal chokepoint).
        //
        // The shaders-ON full-pipeline dest render (SecondaryWorldRenderCore §4.1) arms a GLOBAL
        // GL_CLIP_DISTANCE0 for the whole nested render(). Under an active iris pack, terrain is
        // the ONLY program set that writes gl_ClipDistance[0] (the IS3-injected Patch.SODIUM
        // shader). Iris's NON-terrain programs (Patch.VANILLA entities/sky/particles/clouds —
        // there is NO ENTITY patch type) write no gl_ClipDistance, and those draws funnel through
        // this vanilla trySetup chokepoint, NOT sodium's GLDrawContext. Drawing them with the
        // enable ON but no gl_ClipDistance written is UNDEFINED per the GL spec (non-NVIDIA
        // drivers may cull them). So this guard re-decides the enable PER DRAW from the store's
        // armed INTENT.
        //
        // KEYING (load-bearing, port-note §4.0): key on FrontClipping.capture().enabled — the
        // store's armed intent — and toggle GL_CLIP_DISTANCE0 with RAW GL here, NOT via
        // FrontClipping.enableGlClipDistance/disableGlClipDistance. Those consult the private
        // glClipEnabled cache and early-return when it already agrees, so a per-draw glDisable
        // routed through them would leave the store reading "enabled" while GL is off, and the
        // store would then decline to re-enable — a suppressed draw's disable could stick across
        // the next terrain draw. Raw per-draw toggling keeps each draw independent. trySetup is
        // PER-DRAW, so no latch/restore is needed (unlike the sodium side, whose setContext fires
        // once per pass and needs the endDraw restore).
        //
        // IS3 V6 FOLD — the arm decision is the store's live intent OR the pass-scoped full-pipeline
        // override (armed by the belt while M4 clobbers the live store mid-pass; §4.1). Off the
        // full-pipeline path FullPipelineClipState is never armed, so this reduces to the live
        // capture().enabled and the live-store plane (GL-state identical to the plain-sodium path).
        boolean overrideArmed = FullPipelineClipState.isArmed();
        boolean clipArmed = overrideArmed || FrontClipping.capture().enabled;
        boolean clipEnabledThisDraw = false;

        if (loc >= 0) {
            // Upload the armed plane (keep-all {0,0,0,1} when clipping is disabled — byte-neutral).
            // Under the full-pipeline override, source the FROZEN belt plane — the live store was
            // reset to keep-all by M4's mid-pass disableClipping(), so reading it would clip nothing.
            if (overrideArmed) {
                GL20.glUniform4f(
                    loc,
                    FullPipelineClipState.getPlaneX(),
                    FullPipelineClipState.getPlaneY(),
                    FullPipelineClipState.getPlaneZ(),
                    FullPipelineClipState.getPlaneW()
                );
            } else {
                GL20.glUniform4f(
                    loc,
                    FrontClipping.getPlaneX(),
                    FrontClipping.getPlaneY(),
                    FrontClipping.getPlaneZ(),
                    FrontClipping.getPlaneW()
                );
            }
            if (clipArmed) {
                // Re-assert the enable for this injected draw — an intervening un-injected draw
                // may have disabled it (raw, per-draw; never through the store's cache).
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
                clipEnabledThisDraw = true;
            }
        } else if (clipArmed) {
            // Un-injected program while a plane is armed: suppress the enable so this draw is
            // DEFINED-and-unclipped instead of UNDEFINED. Restored by the next injected draw's
            // glEnable above; no latch (trySetup is per-draw).
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
        }

        // TINT (SEAM_BAND_HANDOFF §4.1, diagnostic, default-off): upload the per-painter debug
        // tint beside the clip plane, from the same live store the painters' Snapshot brackets
        // feed. Same chokepoint, same location-cache discipline (id-reuse invalidation via
        // ClipUniformLocationCache.clear()), zero cost when the lever is off (one static read).
        if (com.warwa.seamlessportals.render.SeamTint.ENABLED) {
            Integer tintCached = ClipUniformLocationCache.getTint(programId);
            int tintLoc;
            if (tintCached == null) {
                tintLoc = GlStateManager._glGetUniformLocation(
                    programId, ShaderCodeTransformation.TINT_UNIFORM_NAME);
                ClipUniformLocationCache.putTint(programId, tintLoc);
            } else {
                tintLoc = tintCached;
            }
            if (tintLoc >= 0) {
                GL20.glUniform4f(
                    tintLoc,
                    FrontClipping.getTintR(),
                    FrontClipping.getTintG(),
                    FrontClipping.getTintB(),
                    FrontClipping.getTintA()
                );
            }
        }

        // §4.7 discriminators 1+2: lever-gated (-Dseamlessportals.clipProbe), 1Hz-latched,
        // one-shot-per-pass diagnose-first evidence. Byte-inert at the default (ENABLED short-
        // circuits before any work). Records program / loc / enable-decision for the first N draws
        // of an armed full-pipeline pass so the self-run round confirms terrain->loc>=0/enabled and
        // entity+sky->loc==-1/disabled, and that the uploader fires on the iris terrain program.
        ClipDiscriminatorProbe.recordDraw(programId, loc, clipArmed, clipEnabledThisDraw);
    }
}
