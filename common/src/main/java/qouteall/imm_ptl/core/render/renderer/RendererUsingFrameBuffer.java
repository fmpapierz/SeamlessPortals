package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.QueryManager;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.List;

// S12-A (U10 A1 slice) port disposition: NEW — VERBATIM IP:render/renderer/RendererUsingFrameBuffer.java
// logic, re-expressed onto the mod's PROVEN 26.2 FBO/RenderTarget surface. The renderMode=COMPATIBILITY
// renderer (A1): renders each dest world into a same-size SECONDARY FBO, then composites that FBO through the
// portal area onto the main frame. One-layer only (no portal-in-portal). Pinned by IPCGlobal.java:18
// (`rendererUsingFrameBuffer`) + PortalRenderer.switchToCorrectRenderer's `case compatibility`. Held/inert
// until S13; A1 PERIPHERY (CUTOVER_SPEC §6.5) — NOT in the S13 cutover set, runtime-verified at S18 (C4);
// its absence at cutover is status quo, not regression. Default renderMode=normal (the stencil renderer), so
// this path is opt-in.
//
// 26.2 RE-EXPRESSIONS (each api-map-sanctioned; the mod's SecondaryFrameBuffer/GuiPortalRendering path
// already crossed every one of these bridges; no IP LOGIC deviated):
//   * Minecraft.getMainRenderTarget()              -> client.gameRenderer.mainRenderTarget() (G14/C7). 2 sites.
//   * ((IEMinecraftClient) client).ip_setFrameBuffer(fb) — KEPT (the landed duck; render-core C5). On 26.2 the
//        main target is GameRenderer's private final field, LevelRenderer re-reads it every frame, so the swap
//        IS the render-routing. RendererUsingStencil/GuiPortalRendering use this identical swap.
//   * fb.bindWrite(true) / oldFrameBuffer.bindWrite(true) -> DROPPED (render-sub G8): 26.2 RenderTarget has no
//        bind method / no FBO id — the ip_setFrameBuffer swap above supersedes both binds (same as
//        GuiPortalRendering.java:106,117).
//   * GlStateManager._clearColor(1,0,1,1) + _clearDepth(1) + _clear(COLOR|DEPTH) on the just-swapped secondary
//        FBO — GONE (render-sub G7). Re-expressed as a device-level clear of the SECONDARY target's textures
//        (CommandEncoder.clearColorAndDepthTextures), the GuiPortalRendering.java:94 idiom.
//   * RenderSystem.getProjectionMatrix()           -> getCurrentProjectionMatrix() (inherited; render-core
//        G27/G19). renderPortalArea ignores the projection param on 26.2 (the pass reads the uploaded
//        projection buffer); drawPortalAreaWithFramebuffer likewise composites via a full-screen pass.
//
// SIGN NOTE (D4.4 / R5, CUTOVER_SPEC §2): the ONLY depth constant here is the secondary-FBO depth CLEAR. IP's
// _clearDepth(1) = window depth 1.0 = FAR (1.21.3 normal-Z); 26.2 reversed-Z FAR = 0.0, so the clear is
// FLIPPED 1.0 -> 0.0 (CUTOVER_SPEC §2 ground truth "Depth CLEAR value = 0.0 = FAR"; same flip
// GuiPortalRendering.java:96 ships). Purpose is unchanged: the FBO's depth starts at FAR so the dest terrain
// drawn next all passes. The magenta color is direction-independent. glDisable(GL_STENCIL_TEST) is raw-GL,
// unchanged. The FBO->main COMPOSITE (renderSecondBufferIntoMainBuffer) is the CUTOVER_SPEC row-12 FBO-mode
// case: it delegates to MyRenderHelper.drawPortalAreaWithFramebuffer (this slice) — see that method for its
// STEP-3.5 NEAR-shield / composite derivation.
public class RendererUsingFrameBuffer extends PortalRenderer {
    SecondaryFrameBuffer secondaryFrameBuffer = new SecondaryFrameBuffer();

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        renderPortals(modelView);
    }

    @Override
    public void onHandRenderingEnded() {

    }

    @Override
    public void finishRendering() {

    }

    @Override
    public void prepareRendering() {
        secondaryFrameBuffer.prepare();

        GlStateManager._enableDepthTest();

        GL11.glDisable(GL11.GL_STENCIL_TEST);

        IPPortingLibCompat.setIsStencilEnabled(client.gameRenderer.mainRenderTarget(), false);
    }

    protected void doRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        if (PortalRendering.isRendering()) {
            //only support one-layer portal
            return;
        }

        if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }

        PortalRendering.pushPortalLayer(portal);

        RenderTarget oldFrameBuffer = client.gameRenderer.mainRenderTarget();

        // 26.2 (G8): the ip_setFrameBuffer swap routes rendering into the secondary FBO; no bindWrite.
        ((IEMinecraftClient) client).ip_setFrameBuffer(secondaryFrameBuffer.fb);

        // 26.2 (G7): IP's magenta _clearColor + _clearDepth(1) + bound-FBO _clear is GONE — device-clear the
        // secondary target's textures. Depth clear = 0.0 (R5 reversed-Z FAR; was 1.0).
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            secondaryFrameBuffer.fb.getColorTexture(), new Vector4f(1, 0, 1, 1),
            secondaryFrameBuffer.fb.getDepthTexture(), 0.0
        );
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        renderPortalContent(portal);

        // 26.2 (G8): restore the main target via ip_setFrameBuffer; no bindWrite.
        ((IEMinecraftClient) client).ip_setFrameBuffer(oldFrameBuffer);

        PortalRendering.popPortalLayer();

        CHelper.enableDepthClamp();
        renderSecondBufferIntoMainBuffer(portal, modelView);
        CHelper.disableDepthClamp();

        MyRenderHelper.debugFramebufferDepth();
    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }

    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }

    private boolean testShouldRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        FrontClipping.updateInnerClipping(modelView);
        return QueryManager.renderAndGetDoesAnySamplePass(() -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                getCurrentProjectionMatrix(),
                true, true,
                true, true
            );
        });
    }

    private void renderSecondBufferIntoMainBuffer(Portal portal, Matrix4f modelView) {
        MyRenderHelper.drawPortalAreaWithFramebuffer(
            portal,
            secondaryFrameBuffer.fb,
            modelView,
            getCurrentProjectionMatrix()
        );
    }

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
