package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import qouteall.imm_ptl.core.portal.LoadingIndicatorEntity;

/**
 * Renderer for {@link LoadingIndicatorEntity} (IP {@code render/LoadingIndicatorRenderer}).
 *
 * <p>26.2 re-expression (render-core G4): the 1.21.3 {@code render(...)} contract is GONE. IP's
 * {@code render()} body was entirely commented out (the text-label rendering is disabled), so the
 * faithful port renders nothing: {@code createRenderState()} returns a base
 * {@code EntityRenderState}, and {@code submit()} is overridden EMPTY — which also suppresses the
 * base leash/name-tag submission, matching IP's no-{@code super}-call empty render.
 * {@code getTextureLocation} (IP returned {@code null}) is DROPPED — not part of the 26.2 base
 * contract.
 *
 * <p>Registered via the S0 renderer-registration seam
 * ({@code PlatformHelper.registerEntityRenderer}) at S13 step 5 for {@code LoadingIndicatorEntity}
 * (S00-seam-inventory B4; EXECUTION_PLAN Appendix A.9).
 */
public class LoadingIndicatorRenderer extends EntityRenderer<LoadingIndicatorEntity, EntityRenderState> {
    public LoadingIndicatorRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    @Override
    public void submit(
        EntityRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera
    ) {
        // IP's render() body is entirely commented out — render nothing.
        // (Overriding submit() empty deliberately skips the base leash/name-tag submission,
        // reproducing IP's empty render() that never called super.)
    }
}
