package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * C2-0 probe P7b — {@code migration/C2_DESIGN.md} §4 P7(b), stage C2-0 deliverable 4(ii).
 *
 * <p>SIBLING of {@code com.warwa.seamlessportals.mixin.client.GlCommandEncoderClipMixin} (the
 * vanilla clip-plane uploader). Same seam — {@code GlCommandEncoder.trySetup(GlRenderPass,
 * Collection)} at RETURN — but LOG-ONLY. The question it answers: do Sodium terrain draws pass
 * through Mojang's {@code trySetup} with the SODIUM program bound (deciding whether the C2-2 clip
 * transport's "candidate A" — reuse the existing {@code GlCommandEncoderClipMixin} uploader with
 * zero new code — actually reaches the sodium pipeline), and does that bound program already
 * declare {@code seamlessportals_ClipPlane} ({@code glGetUniformLocation != -1})? At C2-0 the
 * sodium clip source patch does not exist yet, so a sodium program is EXPECTED to report
 * {@code hasUniform=false}; a vanilla program (patched by the shipped
 * {@code ShaderManagerCompilationCacheMixin}) reports {@code true}.
 *
 * <p>Gated behind {@code -Dseamlessportals.compatProbe=true}, {@code require = 0}, LOG-ONLY. The GL
 * program query + {@code glGetUniformLocation} are throttled to at most 1 Hz (this seam fires
 * per-draw; a per-draw GL round-trip + log on the render thread would stall it — the ~130 ms
 * log4j-stall rule). Distinct {@code (program, hasUniform)} pairs are accumulated; one summary line
 * per NEW pair. Raw GL is used exactly as the sibling does — a uniform-location query is uncached
 * and safe under the 26.2 GL-state invariant (never touch a {@code GlStateManager}-cached state).
 * The class name carries {@code Sodium} so gate 1 weaves it only when Sodium is present.
 */
@Mixin(targets = "com/mojang/blaze3d/opengl/GlCommandEncoder")
public abstract class MixinSodiumProbe_GlCommandEncoder {

    private static final boolean seamlessportals$probe = Boolean.getBoolean("seamlessportals.compatProbe");

    private static final long seamlessportals$sampleIntervalNanos = 1_000_000_000L; // 1 Hz

    private static volatile long seamlessportals$lastSampleNanos = 0L;

    /** Distinct {@code programId + ":" + hasUniform} pairs already logged. */
    private static final Set<String> seamlessportals$seenPairs =
        Collections.synchronizedSet(new HashSet<>());

    @Inject(
        method = "trySetup(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$probeTrySetup(CallbackInfoReturnable<Boolean> cir) {
        if (!seamlessportals$probe) {
            return;
        }
        // Only a successful setup precedes a draw.
        if (Boolean.FALSE.equals(cir.getReturnValue())) {
            return;
        }
        // Throttle the GL round-trip to <=1 Hz (per-draw seam).
        long now = System.nanoTime();
        if (now - seamlessportals$lastSampleNanos < seamlessportals$sampleIntervalNanos) {
            return;
        }
        seamlessportals$lastSampleNanos = now;

        int programId = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId <= 0) {
            return;
        }
        int loc = GlStateManager._glGetUniformLocation(programId, ShaderCodeTransformation.UNIFORM_NAME);
        boolean hasUniform = loc >= 0;
        String pair = programId + ":" + hasUniform;
        if (!seamlessportals$seenPairs.add(pair)) {
            return; // one summary per new pair
        }
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
            "[COMPAT PROBE P7b] trySetup RETURN: boundProgram={} hasClipUniform({})={} (loc={})",
            programId, ShaderCodeTransformation.UNIFORM_NAME, hasUniform, loc);
    }
}
