package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.portal.Portal;

// S12-A (U10 A1 slice) port disposition: NEW — VERBATIM IP:render/renderer/RendererDummy.java. The
// renderMode=none renderer: every override is a no-op, so no portal view is ever rendered. Pinned by
// IPCGlobal.java:19 (`rendererDummy = new RendererDummy()`) + PortalRenderer.switchToCorrectRenderer's
// `case none`. No GONE 26.2 API is touched (PoseStack survives, com.mojang.blaze3d.vertex.PoseStack), so
// this is a byte-for-byte port. Held/inert until S13.
//
// SIGN NOTE (D4.4): no depth/stencil/clip/transform surface — R5 reversed-Z does not touch this class.
public class RendererDummy extends PortalRenderer {
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }

    @Override
    public void prepareRendering() {

    }

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {

    }

    // IP declares this WITH @Override, but neither IP's PortalRenderer nor the landed (Slice A)
    // PortalRenderer declares onAfterTranslucentRendering, and IP never CALLS it (grep: zero dot-call
    // sites) — the annotation overrode nothing even upstream (an orphaned no-op). The @Override is dropped
    // (it cannot compile against a parent that lacks the method) while IP's no-op BODY is kept verbatim for
    // source-shape fidelity. Not a LOGIC deviation: the method was, and remains, an unreachable no-op.
    public void onAfterTranslucentRendering(Matrix4f modelView) {

    }

    @Override
    public void onHandRenderingEnded() {

    }

    @Override
    public void finishRendering() {

    }

    protected void doRenderPortal(
        Portal portal,
        PoseStack matrixStack
    ) {

    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {

    }
}
