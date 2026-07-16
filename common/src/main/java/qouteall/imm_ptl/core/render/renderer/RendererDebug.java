package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.QueryManager;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.List;

// S12-A (U10 A1 slice) port disposition: NEW — VERBATIM IP:render/renderer/RendererDebug.java logic. The
// renderMode=debug renderer: it clears the WHOLE main frame magenta then renders one portal's dest world
// directly onto the main target (no FBO swap, no compositing) — a diagnostic that shows the raw dest render.
// Pinned by IPCGlobal.java:20 (`rendererDebug = new RendererDebug()`) + PortalRenderer.switchToCorrectRenderer's
// `case debug`. Held/inert until S13; only reachable via renderMode=debug (opt-in). A1 periphery
// (CUTOVER_SPEC §6.5) — runtime-verified at S18, never at the S13 cutover.
//
// 26.2 RE-EXPRESSIONS (each api-map-sanctioned; no IP LOGIC deviated):
//   * RenderSystem.getProjectionMatrix()   -> getCurrentProjectionMatrix() (inherited; render-core G27/G19,
//        the Slice-A PortalRenderer convention). renderPortalArea installs the passed projection onto
//        RenderSystem's projection buffer around its draw (S13-I nested-layer fix; the pass reads that buffer).
//   * GlStateManager._clearColor(1,0,1,1) + _clearDepth(1) + _clear(COLOR|DEPTH, ON_OSX) — ALL GONE on 26.2
//        (render-sub G7: no _clearColor/_clearDepth, _clear is 1-arg, ON_OSX moot). Re-expressed as a
//        device-level clear of the CURRENT main target's textures (the exact idiom GuiPortalRendering.java:94
//        uses): CommandEncoder.clearColorAndDepthTextures(colorTex, magenta, depthTex, farDepth).
//   * Minecraft.getMainRenderTarget()       -> client.gameRenderer.mainRenderTarget() (render-core G14/C7).
//
// SIGN NOTE (D4.4 / R5, CUTOVER_SPEC §2 ground truth "Depth CLEAR value = 0.0 = FAR"): IP's _clearDepth(1)
// wrote window depth 1.0 = FAR under 1.21.3 normal-Z. On 26.2 reversed-Z the FAR clear value is 0.0, so the
// depth clear is FLIPPED 1.0 -> 0.0. Same flip GuiPortalRendering.java:96 already ships. The magenta color
// (1,0,1,1) is direction-independent and stays verbatim. glDisable(GL_STENCIL_TEST) is raw-GL, unchanged.
public class RendererDebug extends PortalRenderer {
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        renderPortals(modelView);
    }

    // IP declares this WITH @Override, but the parent (IP's + Slice A's PortalRenderer) never declares
    // onAfterTranslucentRendering and IP never CALLS it — an orphaned no-op. @Override dropped so it
    // compiles against the parent that lacks the method; IP's no-op body kept verbatim. Not a LOGIC deviation.
    public void onAfterTranslucentRendering(Matrix4f modelView) {

    }

    @Override
    public void onHandRenderingEnded() {

    }

    @Override
    public void prepareRendering() {

    }

    @Override
    public void finishRendering() {

    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {

    }

    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }

    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        if (RenderStates.getRenderedPortalNum() != 0) {
            return;
        }

        if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }

        PortalRendering.pushPortalLayer(portal);

        // 26.2 (render-sub G7 / render-core G14): IP's magenta _clearColor + _clearDepth(1) + bound-FBO
        // _clear(COLOR|DEPTH) is GONE — device-clear the current main target's textures. Depth clear = 0.0
        // (R5 reversed-Z FAR; was 1.0). The main target always carries a depth attachment (useDepth=true).
        RenderTarget mainRt = client.gameRenderer.mainRenderTarget();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            mainRt.getColorTexture(), new Vector4f(1, 0, 1, 1),
            mainRt.getDepthTexture(), 0.0
        );
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        renderPortalContent(portal);

        PortalRendering.popPortalLayer();
    }

    private boolean testShouldRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        return QueryManager.renderAndGetDoesAnySamplePass(() -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                getCurrentProjectionMatrix(),
                true, true,
                true, true);
        });
    }

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
