package qouteall.imm_ptl.core.mixin.client.render.isometric;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B (render client-mixin half) — IP {@code MixinGameRenderer_Isometric}
 * ({@code IP:mixin/client/render/isometric/MixinGameRenderer_Isometric.java}), 26.2 DEFERRED-with-note
 * (mixin-client.md §8 NEEDS-RETARGET).
 *
 * <p><b>Target GONE; the isometric-debug projection re-anchors onto the Camera.</b> IP HEAD-cancelled
 * {@code GameRenderer.getProjectionMatrix(F)} to substitute
 * {@code TransformationManager.getIsometricProjection()} when {@code TransformationManager.isIsometricView}.
 * {@code GameRenderer.getProjectionMatrix} is GONE on 26.2 — the projection is built inside {@code Camera}
 * ({@code setupPerspective}/{@code setupOrtho}, {@code Camera.java:326-330}) and consumed as
 * {@code cameraState.projectionMatrix}. The faithful re-expression cancels/replaces the
 * {@code Camera.update} → {@code setupPerspective} call with {@code setupOrtho}, OR overwrites
 * {@code cameraState.projectionMatrix} post-extract, when {@code isIsometricView}.
 *
 * <p><b>DEFERRED to S13.</b> Isometric view is an optional debug feature (IP itself gates it behind a debug
 * flag); its projection re-anchor is a Camera-layer change best designed against the live extract pipeline
 * alongside the R13k camera work. Held/UNREGISTERED; no-op today. Original IP handler for provenance:
 *
 * <pre>
 * &#64;Inject(method = "getProjectionMatrix", at = &#64;At("HEAD"), cancellable = true)
 * private void onGetBasicProjectionMatrix(float d, CallbackInfoReturnable&lt;Matrix4f&gt; cir) {
 *     if (TransformationManager.isIsometricView) {
 *         cir.setReturnValue(TransformationManager.getIsometricProjection());
 *     }
 * }
 * </pre>
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Isometric {
    // DEFERRED — see class javadoc (getProjectionMatrix GONE; re-anchor onto Camera.setupPerspective/
    // setupOrtho or cameraState.projectionMatrix post-extract; optional isometric-debug feature, S13).
}
