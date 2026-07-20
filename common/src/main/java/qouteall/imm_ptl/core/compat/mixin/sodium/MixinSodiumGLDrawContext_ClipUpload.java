package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.warwa.seamlessportals.render.FrontClipping;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * C2-2 D3 — the sodium clip-plane UPLOADER + the D10-residual definedness guard (design
 * {@code migration/C2_DESIGN.md} §1 C2-2 deliverable 2 / §3.3.2 upload candidates).
 *
 * <h2>Why this seam and not {@code ShaderChunkRenderer.begin} (the specced candidate-B site)</h2>
 * javap of the real 0.9.1 jar REFUTES the candidate-B premise: {@code ShaderChunkRenderer.begin}
 * bytecode is exactly {@code activeProgram = compileProgram(pass)} ({@code putfield}, offsets
 * 0-9) — it binds NO GL program, so a {@code GL_CURRENT_PROGRAM} query at begin RETURN reads
 * whatever program the PREVIOUS draw left bound (a provably wrong upload target). The pass's
 * program is actually bound in {@code GLDrawContext.setContext} ({@code GlRenderPipeline →
 * GlProgram.getProgramId() → GlStateManager._glUseProgram}, bytecode offsets 24-34, re-asserted
 * at 70-72 before return), invoked exactly once per terrain pass at
 * {@code DefaultChunkRenderer.render} offset 270, strictly inside the {@code begin}(offset 6)
 * … {@code end}(offset 542) bracket. So the candidate-B upload is RE-SITED here: same mirrored
 * {@code GlCommandEncoderClipMixin} body ({@code GL_CURRENT_PROGRAM} query +
 * {@code _glGetUniformLocation} + {@code glUniform4f} + per-program location cache), at the seam
 * where the program is PROVABLY bound, once per pass — which is precisely the C4
 * SUBMIT_ORDER_UNIFORM timing (the plane is constant across a pass's terrain draws).
 *
 * <h2>Candidate-A ledger (in-code per the stage spec)</h2>
 * Candidate A — the shipped vanilla {@code GlCommandEncoderClipMixin} at {@code trySetup} RETURN —
 * is now STATICALLY PROVEN to also cover every sodium terrain batch: {@code GLDrawBatch.draw} →
 * Mojang {@code RenderPass.multiDrawIndexed(PointerBuffer,…)} → {@code GlCommandEncoder
 * .executeDraws} → {@code trySetup} (mc262-ref {@code GlRenderPass.java:156} /
 * {@code GlCommandEncoder.java:517}), with the program bound before trySetup returns. The
 * double-upload (here at pass start, there per batch) writes the SAME store value — idempotent
 * and benign. This mixin still ships because (a) the sodium transport must not silently depend on
 * the vanilla mixin's coverage surviving refactors, and (b) it is the only seam that can host the
 * definedness guard below.
 *
 * <h2>The definedness guard (honest replacement for the retired C2-1 interim bracket)</h2>
 * The retired {@code MixinSodiumShaderChunkRenderer_InterimClipBracket} (D10) UNCONDITIONALLY
 * suppressed {@code GL_CLIP_DISTANCE0} across sodium terrain draws because 0.9.1's shaders wrote
 * no {@code gl_ClipDistance}. With the C2-2 source patch they DO — the enable is now DEFINED and
 * the unconditional bracket dies. The RESIDUAL exposure (stage spec item 3): a sodium terrain
 * program that did NOT get patched (resource-pack-replaced source missing our anchors, or a
 * driver dead-strip eliminating the uniform → location -1) drawing while the {@code com.warwa}
 * {@code FrontClipping} store has the enable armed would be UNDEFINED again. The guard closes it
 * surgically: location == -1 AND store-enabled → suppress the enable for exactly this pass,
 * restore at {@code endDraw} ({@code DefaultChunkRenderer.render} offset 537, before
 * {@code end}); stale-latch self-heal at the next {@code setContext} (the retired bracket's
 * NOTE-7 discipline). Normally (patched program) the guard is dead: location ≥ 0.
 * {@code FrontClipping} arms {@code GL_CLIP_DISTANCE0} via FOUR paths (lens-B corrected):
 * {@code setupOuterClipping / setupInnerClipping(ForEntities) / setupKillSwitchClipping /
 * restore(Snapshot{enabled=true})} — the LAST being the qouteall-bridge path the flag-ON portal
 * renderer actually uses. Every path arms a real computed plane alongside, so the store-enabled
 * check remains exactly the "a plane is armed" condition.
 *
 * <h2>Gating / neutrality</h2>
 * Flag-agnostic-weave discipline (§6.2): no invoker/PortalRendering gate — when clipping is
 * disabled the store holds keep-all {@code {0,0,0,1}} and the upload is byte-neutral (and with
 * the store disabled the guard cannot fire). Sodium-absent/flag-OFF: not woven (compat plugin).
 * VULKAN (D4): structurally unreachable — {@code DrawContext.create()} instantiates
 * {@code GLDrawContext} only when {@code DrawBackend.BACKEND == OPENGL} (census (h)); the VK
 * contexts are separate classes this mixin never touches.
 *
 * <h2>GL-state discipline</h2>
 * (memory {@code 26-2-glstate-and-fbo-invariants}) {@code GL_CLIP_DISTANCE0} is NOT
 * GlStateManager-cached — raw {@code GL11.glEnable/glDisable} is the store's own pattern.
 * {@code GL_CURRENT_PROGRAM} is read-only observation of real GL state (consistent with the
 * GlStateManager cache: {@code setContext} binds through {@code _glUseProgram}). The location
 * cache mirrors {@code GlCommandEncoderClipMixin}'s (per-program-id, first-use query; same
 * accepted program-id-reuse exposure — sodium's terrain pipelines live in the static
 * {@code ShaderChunkRenderer.programs} map and are compiled once per pass type, so churn is nil).
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = GLDrawContext.class, remap = false)
public abstract class MixinSodiumGLDrawContext_ClipUpload {

    /** Cache: programId → {@code seamlessportals_ClipPlane} location (or -1 if absent). */
    private static final Map<Integer, Integer> ip_clipPlaneLocationCache = new HashMap<>();

    /** D10-residual latch: the enable was suppressed for the current pass. */
    @Unique
    private boolean ip_clipDistance0Suppressed = false;

    @Inject(method = "setContext", at = @At("RETURN"), remap = false)
    private void ip_uploadClipPlaneOnSetContext(
        RenderPass pass, RenderPipeline pipeline, CallbackInfo ci
    ) {
        // Stale-latch self-heal (a throw that skipped endDraw must not leak the suppression into
        // the next pass — the retired bracket's NOTE-7 discipline). C2-2 verify lens B CORRECTION:
        // the re-enable MUST consult the store — an unconditional glEnable here could desync raw
        // GL from FrontClipping's cache (store says disabled → disableGlClipDistance early-returns
        // → the enable sticks) and re-create the undefined class for unpatched VANILLA programs.
        // Store-gated = exact-restore in all cases (store-enabled: restores what suppression
        // removed; store-disabled: GL is already off and stays off).
        if (ip_clipDistance0Suppressed) {
            ip_clipDistance0Suppressed = false;
            if (FrontClipping.capture().enabled) {
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            }
        }

        int programId = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId <= 0) {
            return;
        }
        Integer cached = ip_clipPlaneLocationCache.get(programId);
        int loc;
        if (cached == null) {
            loc = GlStateManager._glGetUniformLocation(
                programId, ShaderCodeTransformation.UNIFORM_NAME);
            ip_clipPlaneLocationCache.put(programId, loc);
        }
        else {
            loc = cached;
        }

        if (loc >= 0) {
            // The upload — keep-all {0,0,0,1} when clipping is disabled (byte-neutral).
            GL20.glUniform4f(
                loc,
                FrontClipping.getPlaneX(),
                FrontClipping.getPlaneY(),
                FrontClipping.getPlaneZ(),
                FrontClipping.getPlaneW()
            );
        }
        else if (FrontClipping.capture().enabled) {
            // D10-residual definedness guard: this terrain program writes no gl_ClipDistance
            // (unpatched source or driver dead-strip) while a clip plane is armed — drawing with
            // the enable on would be UNDEFINED per the GL spec. Suppress for exactly this pass.
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            ip_clipDistance0Suppressed = true;
        }
    }

    @Inject(method = "endDraw", at = @At("HEAD"), remap = false)
    private void ip_restoreClipEnableOnEndDraw(CallbackInfo ci) {
        if (ip_clipDistance0Suppressed) {
            ip_clipDistance0Suppressed = false;
            // Restore the exact pre-pass state — store-gated for the same lens-B reason as the
            // setContext heal (if the store was disarmed mid-pass, GL must stay off).
            if (FrontClipping.capture().enabled) {
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            }
        }
    }
}
