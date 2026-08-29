package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEEntityRenderState;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.PerEntityClipBracket;

/**
 * S12-A (Slice C) — R3 mixins 3 (submitEntities HEAD/TAIL anchors) + 4 (the per-entity submit
 * {@code @WrapOperation}) of 4 (S11-R3-clip-bracketing.md §3.3). These re-express IP 1.21.3's
 * {@code MixinWorldRenderer} entity-loop hooks (begin / per-entity {@code renderEntity} wrap / end) onto 26.2's
 * {@code LevelRenderer.submitEntities} — the per-entity SUBMIT boundary that replaces IP's per-entity RENDER
 * boundary (there is no mid-batch flush on 26.2; the draw-call bracket comes from the submit-side data model,
 * design §0.1). Both HEAD/TAIL and the WrapOperation target the SAME method ({@code submitEntities}), so IP's
 * one-class organisation is preserved in one mixin here.
 *
 * <p>A SEPARATE mixin from {@link MixinLevelRenderer} (the R4 ViewArea install) purely to keep the two Slice-C
 * concerns in their own files; both are {@code @Mixin(LevelRenderer.class)} and compose.
 *
 * <ul>
 *   <li><b>HEAD</b> (design §3.3 row 1): resets the per-pass seam state
 *       ({@code PerEntityClipBracket.onFrameSubmitBegin(storage)}) and runs IP's CASE-3 whole-pass inner clip
 *       ({@code CrossPortalEntityRenderer.onBeginRenderingEntitiesAndBlockEntities(viewRotationMatrix)}).</li>
 *   <li><b>per-entity {@code @WrapOperation}</b> (design §3.3 row 2): a TAGGED state (its {@link IEEntityRenderState}
 *       clip-context Entity is non-null) routes to {@code CrossPortalEntityRenderer.submitMainPassEntity(...)},
 *       which submits it into its own clip-order band (Mechanism A) / defers an isolated bracket (Mechanism B);
 *       if that returns {@code true} (handled) the vanilla submit is skipped, else — and for every untagged
 *       state — the vanilla {@code original.call(...)} runs unchanged.</li>
 *   <li><b>TAIL</b> (design §3.3 row 3, fired before the caller's {@code :282} entityRenderStates.clear()):
 *       {@code CrossPortalEntityRenderer.onEndRenderingEntitiesAndBlockEntities(...)} → CASE-2 destination-side
 *       projections through the seam.</li>
 * </ul>
 *
 * <p><b>Storage identity.</b> {@code output} is the pass's own {@code SubmitNodeStorage}
 * ({@code LevelRenderer.submitFeatures} passes {@code this.submitNodeStorage}, {@code 26.2:LevelRenderer.java:174,281}),
 * so the seam's per-{@code LevelRenderer}-instance (per-storage) scoping keys on it correctly across the
 * mid-framegraph nested dest pass (Verifier-1 P2 hardening, design §1.2.5).
 *
 * <p><b>View rotation.</b> The CASE-3 modelView is {@code levelRenderState.cameraRenderState.viewRotationMatrix}
 * (the world→view rotation the 26.2 model-view stack applies to camera-relative submit poses,
 * {@code LevelRenderer.render:170-172}); the exact equivalence + the extract-vs-render timing of this
 * submit-side anchor vs the driver's persistent store finalise at S13 (design §6; CUTOVER_SPEC §4).
 *
 * <p>REGISTERED + LIVE (seamlessportals-ip-client.mixins.json "client" array) — fires per pass on every
 * LevelRenderer instance (main + secondaries + the same-dim scratch submit) since S13.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer_CrossPortalEntity {

    @Shadow
    public abstract EntityRenderDispatcher entityRenderDispatcher();

    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void seamlessportals$onSubmitEntitiesBegin(
        PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci
    ) {
        if (output instanceof SubmitNodeStorage storage) {
            PerEntityClipBracket.onFrameSubmitBegin(storage);
        }
        // §2b probe (dp/sub in the [ENT-PROBE] line): dest submit passes + the entity-state count
        // the submit stage RECEIVES — fires for all three routes (decomposed submitFeatures,
        // same-dim invokeSubmitEntities, compat nested render) via this one submitEntities anchor.
        if (qouteall.imm_ptl.core.render.EntityVisibilityProbe.ENABLED
            && qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            qouteall.imm_ptl.core.render.EntityVisibilityProbe.destPasses++;
            qouteall.imm_ptl.core.render.EntityVisibilityProbe.submittedList +=
                levelRenderState.entityRenderStates.size();
        }
        CrossPortalEntityRenderer.onBeginRenderingEntitiesAndBlockEntities(
            levelRenderState.cameraRenderState.viewRotationMatrix
        );
    }

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void seamlessportals$onSubmitEntitiesEnd(
        PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci
    ) {
        if (output instanceof SubmitNodeStorage storage) {
            CrossPortalEntityRenderer.onEndRenderingEntitiesAndBlockEntities(
                this.entityRenderDispatcher(),
                levelRenderState.cameraRenderState,
                poseStack,
                storage
            );
        }
    }

    @WrapOperation(
        method = "submitEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit"
                + "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDD"
                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
        )
    )
    private void seamlessportals$submitEntityClipped(
        EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState cam,
        double x, double y, double z, PoseStack poseStack, SubmitNodeCollector output,
        Operation<Void> original
    ) {
        // The submit x/y/z are already camera-relative (state.pos - cameraPos, 26.2:LevelRenderer.java:660),
        // exactly what submitMainPassEntity → the seam feed back into dispatcher.submit.
        // §2b probe (pes/h in the [ENT-PROBE] line): per-entity submits inside portal rendering.
        boolean probeThis = qouteall.imm_ptl.core.render.EntityVisibilityProbe.ENABLED
            && qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering();
        if (probeThis) {
            qouteall.imm_ptl.core.render.EntityVisibilityProbe.perEntitySubmits++;
        }
        Entity tagged = ((IEEntityRenderState) state).ip_getClipContextEntity();
        // ★ CART ROUND 5 DIAGNOSTIC (log-only, unthrottled): the COMPLETE submit anatomy for
        // every near-seam minecart state — tag, pass, outcome — so one crossing run carries the
        // full per-frame story instead of another single-question probe.
        boolean cartProbe = com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                .SEAM_CART_PROBE
            && state instanceof net.minecraft.client.renderer.entity.state.MinecartRenderState
            && com.warwa.seamlessportals.render.CartWindowProbe.nearSeam(state);
        if (tagged != null && output instanceof SubmitNodeStorage storage) {
            boolean handled = CrossPortalEntityRenderer.submitMainPassEntity(
                dispatcher, state, cam, x, y, z, poseStack, storage, tagged
            );
            if (cartProbe) {
                com.warwa.seamlessportals.render.CartWindowProbe.onMinecartSubmit(
                    state, "tagged:" + tagged.getId(),
                    handled ? "seam-handled" : "fell-through-vanilla");
            }
            if (handled) {
                if (probeThis) {
                    qouteall.imm_ptl.core.render.EntityVisibilityProbe.perEntityHandled++;
                }
                return; // clipped submit performed by the seam; skip the vanilla submit
            }
        }
        else if (cartProbe) {
            com.warwa.seamlessportals.render.CartWindowProbe.onMinecartSubmit(
                state, tagged == null ? "UNTAGGED" : "tagged-no-storage", "vanilla");
        }
        original.call(dispatcher, state, cam, x, y, z, poseStack, output);
    }
}
