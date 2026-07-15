package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.mc_utils.WireRenderingHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * The Portal ENTITY's renderer (IP {@code render/PortalEntityRenderer}).
 *
 * <p>26.2 re-expression (render-core G4): the 1.21.3
 * {@code render(T, float, float, PoseStack, MultiBufferSource, int)} contract is GONE; the
 * base is now {@code EntityRenderer<T extends Entity, S extends EntityRenderState>} with the
 * split {@code createRenderState()} / {@code extractRenderState(T, S, float)} (game-thread) /
 * {@code submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)} (render-thread). IP's
 * whole {@code render()} body is a 1:1 mapping onto {@code submit()}; {@code extractRenderState}
 * captures the live {@link Portal} into the render state so submit() can drive the shim hook,
 * overlay, and debug mesh (G4: "extract portal state game-thread-side and submit/flag
 * render-thread-side. The stencil/FBO renderers collect portals elsewhere, so the renderer stays
 * a shim — but with the new 3-method shape").
 *
 * <p>{@code getTextureLocation} is DROPPED: it is not part of the 26.2 base contract
 * (26.2:EntityRenderer.java has no such method); IP's override returned {@code null} anyway
 * (with a dead {@code BreakablePortalEntity} branch already commented out).
 *
 * <p>Registered via the S0 renderer-registration seam
 * ({@code PlatformHelper.registerEntityRenderer}) at S13 step 5 for the Portal entity-type
 * family — NOT the unported Fabric entrypoint (EXECUTION_PLAN Appendix A.9; S00-seam-inventory
 * B4). Runtime-mandatory for S13 rung 1 to render any portal.
 */
@Environment(EnvType.CLIENT)
public class PortalEntityRenderer extends EntityRenderer<Portal, PortalEntityRenderer.PortalEntityRenderState> {

    public PortalEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public PortalEntityRenderState createRenderState() {
        return new PortalEntityRenderState();
    }

    @Override
    public void extractRenderState(Portal portal, PortalEntityRenderState state, float partialTicks) {
        super.extractRenderState(portal, state, partialTicks);
        // Capture the live Portal so the render-thread submit() can drive IP's render() body.
        // The stencil/FBO driver discovers portals via entitiesForRendering() separately
        // (CUTOVER_SPEC §6.2), so reading the entity here mirrors IP, which read the portal
        // live during its per-entity render() pass.
        state.portal = portal;
    }

    @Override
    public void submit(
        PortalEntityRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera
    ) {
        Portal portal = state.portal;

        // Renderer hook — a no-op in the shipping stencil/FBO renderers
        // (RendererUsingStencil/RendererUsingFrameBuffer :95/:167 both do nothing);
        // only the Iris-compat renderers ever consumed it, and they no-op too.
        // Forward-ref to PortalRenderer via IPCGlobal.renderer (U10 / resolves S12).
        IPCGlobal.renderer.renderPortalInEntityRenderer(portal);

        if (OverlayRendering.shouldRenderOverlay(portal)) {
            OverlayRendering.onRenderPortalEntity(portal, poseStack, submitNodeCollector);
        }

        if (IPGlobal.debugRenderPortalShapeMesh && !PortalRendering.isRendering()) {
            // IP: bufferSource.getBuffer(RenderType.lines()) → WireRenderingHelper.render...(matrixStack, ...).
            // 26.2: submitCustomGeometry hands us a VertexConsumer at execute time (RenderTypes.lines(),
            // C39). It snapshots ONE pose (SubmitNodeCollection.submitCustomGeometry does
            // poseStack.last().copy()), so we seed a local PoseStack from that snapshot — the helper
            // pushes an untransformed pose and reads last().pose()/normal(), so the seeded top suffices.
            submitNodeCollector.submitCustomGeometry(
                poseStack,
                RenderTypes.lines(),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    WireRenderingHelper.renderPortalShapeMeshDebug(local, buffer, portal);
                }
            );
        }

        // IP's trailing super.render(...) → the base submit() (leash + name display; a no-op for
        // a nameless Portal, kept for fidelity).
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    /**
     * NEW 26.2-required render state (render-core G4): the extract/submit contract mandates an
     * {@code EntityRenderState}, and the base state cannot carry the {@link Portal}. Carries the
     * live portal reference captured at extract; this is the minimal shim shape — analogous to
     * {@code ImmPtlViewArea} owning fields the 1.21.3 {@code ViewArea} supplied (S11-B §1).
     */
    @Environment(EnvType.CLIENT)
    public static class PortalEntityRenderState extends EntityRenderState {
        public Portal portal;
    }
}
