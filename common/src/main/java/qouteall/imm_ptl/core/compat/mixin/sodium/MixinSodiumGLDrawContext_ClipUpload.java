package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.warwa.seamlessportals.render.ClipUniformLocationCache;
import com.warwa.seamlessportals.render.FrontClipping;
import com.warwa.seamlessportals.render.FullPipelineClipState;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * cache is now the SHARED, IS2-invalidated {@link ClipUniformLocationCache} (IS3 §4.3 fold-in) —
 * the same holder {@code GlCommandEncoderClipMixin} uses, invalidated by
 * {@code GlDeviceClipCacheMixin} on {@code GlDevice.clearPipelineCache}. This retires the old
 * private, un-invalidated, uncapped {@code ip_clipPlaneLocationCache}: with IS3 widening the
 * &gt;=0-location set to iris-transformed terrain, a stale &gt;=0 location fed after a terrain-shader
 * rebuild + program-id reuse would GL_INVALID_OPERATION-spam and mis-write here too. This closes it
 * for {@code clearPipelineCache}-triggering reloads (F3+T / resource-pack apply / {@code GlDevice
 * .close}); an iris-only pipeline rebuild — iris deletes + recompiles its OWN programs via its own
 * {@code glDeleteProgram} and never calls {@code clearPipelineCache} — that recycles a program id
 * stays covered only by the {@code MAX_ENTRIES} cap-clear and the next resource-reload clear:
 * bounded and self-healing, not fully closed (IS3 V4-3 fold).
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = GLDrawContext.class, remap = false)
public abstract class MixinSodiumGLDrawContext_ClipUpload {

    /** D10-residual latch: the enable was suppressed for the current pass. */
    @Unique
    private boolean ip_clipDistance0Suppressed = false;

    @Inject(method = "setContext", at = @At("RETURN"), remap = false)
    private void ip_uploadClipPlaneOnSetContext(
        RenderPass pass, RenderPipeline pipeline, CallbackInfo ci
    ) {
        // IS3 V6 FOLD — the arm decision is the store's live intent OR the pass-scoped full-pipeline
        // override (armed by the belt while M4 clobbers the live store mid-pass; §4.1). Off the
        // full-pipeline path FullPipelineClipState is never armed, so clipArmed == capture().enabled
        // and the plane / enable behaviour below is identical to the shipped decomposed path.
        boolean overrideArmed = FullPipelineClipState.isArmed();
        boolean clipArmed = overrideArmed || FrontClipping.capture().enabled;

        // Stale-latch self-heal (a throw that skipped endDraw must not leak the suppression into
        // the next pass — the retired bracket's NOTE-7 discipline). C2-2 verify lens B CORRECTION:
        // the re-enable MUST consult the arm intent — an unconditional glEnable here could desync raw
        // GL from FrontClipping's cache (store says disabled → disableGlClipDistance early-returns
        // → the enable sticks) and re-create the undefined class for unpatched VANILLA programs.
        // Arm-gated = exact-restore in all cases (armed: restores what suppression removed;
        // disarmed: GL is already off and stays off).
        if (ip_clipDistance0Suppressed) {
            ip_clipDistance0Suppressed = false;
            if (clipArmed) {
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            }
        }

        int programId = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId <= 0) {
            return;
        }
        // IS3 §4.3 FOLD-IN: route this cache through the shared, IS2-invalidated
        // ClipUniformLocationCache (invalidated by GlDeviceClipCacheMixin on
        // GlDevice.clearPipelineCache) instead of the old private, un-invalidated,
        // uncapped ip_clipPlaneLocationCache. IS3 widens the >=0-location program set to the
        // iris-transformed terrain, so a stale >=0 location fed after a sodium/iris terrain-shader
        // rebuild + program-id reuse would be a real GL_INVALID_OPERATION + wrong-uniform-write
        // hazard on this seam too. Sharing the invalidated cache closes it (the vanilla trySetup
        // uploader still lands the correct plane per-batch via the same cache).
        Integer cached = ClipUniformLocationCache.get(programId);
        int loc;
        if (cached == null) {
            loc = GlStateManager._glGetUniformLocation(
                programId, ShaderCodeTransformation.UNIFORM_NAME);
            ClipUniformLocationCache.put(programId, loc);
        }
        else {
            loc = cached;
        }

        if (loc >= 0) {
            // IS5-W FIX 1 — SHADOW-SCOPE CLIP SUPPRESSION (the yaw-keyed shadow-wash killer; fix
            // panel wf_a2d7890c-115). The IS3 clip inject patched iris's SODIUM SHADOW terrain
            // programs too (shadow_sodium_terrain_* — live-log-confirmed loc>=0), and this seam
            // fires inside the dest SHADOW pass with the CAMERA-space plane armed. The shadow
            // program evaluates that plane against the SUN's model-view — a wrong-space half-space
            // whose offset swings ~+/-100 blocks with yaw, clipping the dest casters out of the
            // shadow map at raster stage (empty map -> no occluders -> the brightness wash).
            // Suppress: upload keep-all {0,0,0,1} (defined-never-clips) and SKIP the enable
            // re-assert — the shadow pass draws unclipped casters; the camera pass keeps its
            // correct clip. Short-circuits BOTH the override-armed and live-store branches.
            // Main-world shadow was already receiving keep-all (store disarmed) — byte-identical.
            if (clipArmed && IrisInterface.invoker.isRenderingShadowMap()) {
                if (IPGlobal.isShadowScopeClipFixActive()) {
                    GL20.glUniform4f(loc, 0f, 0f, 0f, 1f);
                    IPGlobal.shadowScopeClipSuppressedCount++;
                    return;
                }
                // A/B baseline (fix lever OFF): count the un-suppressed armed shadow upload —
                // the wash carrier provably firing — then fall through to the pre-fix behavior.
                IPGlobal.shadowScopeClipArmedUploadCount++;
            }
            // The upload — keep-all {0,0,0,1} when clipping is disabled (byte-neutral). Under the
            // full-pipeline override, source the FROZEN belt plane (the live store was reset to
            // keep-all by M4's mid-pass disableClipping(), so reading it would clip nothing).
            if (overrideArmed) {
                GL20.glUniform4f(
                    loc,
                    FullPipelineClipState.getPlaneX(),
                    FullPipelineClipState.getPlaneY(),
                    FullPipelineClipState.getPlaneZ(),
                    FullPipelineClipState.getPlaneW()
                );
                // IS3 V6 FOLD (jB J1): RE-ASSERT the enable here. setContext fires once per terrain
                // group (OPAQUE then TRANSLUCENT are separate renderGroup → DefaultChunkRenderer.render
                // calls), and un-injected iris entity/feature draws BETWEEN those groups disable
                // GL_CLIP_DISTANCE0 (their vanilla trySetup guard) — without this re-enable the
                // translucent terrain group would draw UNCLIPPED. Full-pipeline-override-only so the
                // decomposed path adds no GL call (there the ambient store arm already holds the cap).
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            } else {
                GL20.glUniform4f(
                    loc,
                    FrontClipping.getPlaneX(),
                    FrontClipping.getPlaneY(),
                    FrontClipping.getPlaneZ(),
                    FrontClipping.getPlaneW()
                );
            }
        }
        else if (clipArmed) {
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
            // Restore the exact pre-pass state — arm-gated for the same lens-B reason as the
            // setContext heal (if disarmed mid-pass, GL must stay off). IS3 V6 fold: consult the
            // full-pipeline override too, so a full-pipeline pass restores after an unpatched-terrain
            // suppression even though M4 drove the live store's capture().enabled to false.
            if (FullPipelineClipState.isArmed() || FrontClipping.capture().enabled) {
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            }
        }
    }
}
