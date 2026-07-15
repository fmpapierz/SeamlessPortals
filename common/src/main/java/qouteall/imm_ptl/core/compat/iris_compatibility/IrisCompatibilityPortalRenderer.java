package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;

// S12-A Iris compile-SHELL — VERBATIM IP logic re-expressed onto the 26.2 surface. The renderMode=
// {compatibility,debug} renderer WHEN Iris shaders are active. This class NEVER LOADS on 26.2: Iris has no
// 26.2 build, IrisInterface.invoker.isIrisPresent() returns false forever (base Invoker), and
// PortalRenderer.switchToCorrectRenderer only touches instance/debugModeInstance inside the never-taken
// `isIrisPresent()` branch — so its static init (the two instances) never runs. It must only COMPILE.
// Held/inert until S13; A1 periphery (CUTOVER_SPEC §6.5).
//
// 26.2 RE-EXPRESSIONS (all api-map-sanctioned; the deferred-FBO/blend fidelity is a documented S18/never-run
// Iris gap since Iris never loads):
//   * Minecraft.getMainRenderTarget()   -> client.gameRenderer.mainRenderTarget() (G14/C7).
//   * RenderTarget.bindWrite(boolean)   -> DROPPED (G8): no bind/FBO-id on 26.2 RenderTarget.
//   * fb.setClearColor + fb.clear()     -> device clear of the target textures (G7); depth = 0.0 (R5 FAR).
//   * RenderSystem.colorMask(4-bool)    -> GL11.glColorMask (raw GL; RenderSystem.colorMask is GONE).
//   * RenderSystem.getProjectionMatrix()-> getCurrentProjectionMatrix() (inherited; G27/G19).
//   * IPIrisHelper.newCopyDepthStencil/copyColor -> the 26.2 copyDepthFrom/blitAndBlendToTexture analogs.
//   * MyRenderHelper.drawPortalAreaWithFramebuffer / drawScreenFrameBuffer -> the S12-A draw family.
//
// SIGN NOTE (D4.4 / R5): the only depth constant is the deferred-FBO depth CLEAR: 1.0 -> 0.0 (reversed-Z FAR,
// CUTOVER_SPEC §2). All stencil ops are raw GL (direction-independent). Never runs, so no live sign risk.
public class IrisCompatibilityPortalRenderer extends PortalRenderer {

    public static final IrisCompatibilityPortalRenderer instance = new IrisCompatibilityPortalRenderer(false);
    public static final IrisCompatibilityPortalRenderer debugModeInstance =
        new IrisCompatibilityPortalRenderer(true);

    private SecondaryFrameBuffer deferredBuffer = new SecondaryFrameBuffer();

    // TODO figure out why this field existed in old versions
    private Matrix4f passingModelView = new Matrix4f();

    public boolean isDebugMode;

    public IrisCompatibilityPortalRenderer(boolean isDebugMode) {
        this.isDebugMode = isDebugMode;
    }

    @Override
    public boolean replaceFrameBufferClearing() {
        // 26.2 (G8): IP's client.getMainRenderTarget().bindWrite(false) has no analog — the swap-based routing
        // supersedes the explicit bind.
        return false;
    }

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            return;
        }

        passingModelView = modelView;

        GL11.glDisable(GL_STENCIL_TEST);
    }

    @Override
    public void finishRendering() {
        GL11.glDisable(GL_STENCIL_TEST);
    }

    @Override
    public void prepareRendering() {
        deferredBuffer.prepare();

        // 26.2 (G7): setClearColor(1,0,0,0) + clear() GONE -> device-clear the deferred target. Depth = 0.0
        // (R5 reversed-Z FAR; IP's clear() cleared depth iff useDepth, and the target has depth).
        if (deferredBuffer.fb.getColorTextureView() != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                deferredBuffer.fb.getColorTexture(), new Vector4f(1, 0, 0, 0),
                deferredBuffer.fb.getDepthTexture(), 0.0
            );
        }

        IPPortingLibCompat.setIsStencilEnabled(
            client.gameRenderer.mainRenderTarget(), false
        );

        // Iris now use vanilla framebuffer's depth
        // 26.2 (G8): no bindWrite(false).
    }

    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            // this renderer only supports one-layer portal
            return;
        }

        if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }

        // 26.2 (G8): no client.getMainRenderTarget().bindWrite(true).

        PortalRendering.pushPortalLayer(portal);

        renderPortalContent(portal);

        PortalRendering.popPortalLayer();

        CHelper.enableDepthClamp();

        if (!isDebugMode) {
            // draw portal content to the deferred buffer
            // 26.2 (G8): no deferredBuffer.fb.bindWrite(true); the composite targets the main frame.
            MyRenderHelper.drawPortalAreaWithFramebuffer(
                portal,
                client.gameRenderer.mainRenderTarget(),
                modelView,
                getCurrentProjectionMatrix()
            );
        }
        else {
            MyRenderHelper.drawScreenFrameBuffer(
                client.gameRenderer.mainRenderTarget(),
                true, true
            );
        }

        CHelper.disableDepthClamp();

        // 26.2: RenderSystem.colorMask(4-bool) GONE -> raw GL11.glColorMask (survives).
        GL11.glColorMask(true, true, true, true);

        // 26.2 (G8): no client.getMainRenderTarget().bindWrite(true).
    }

    @Override
    public void invokeWorldRendering(
        WorldRenderInfo worldRenderInfo
    ) {
        MyGameRenderer.renderWorldNew(
            worldRenderInfo,
            Runnable::run
        );
    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {

    }

    private boolean testShouldRenderPortal(Portal portal, Matrix4f modelView) {
        // 26.2 (G8): no deferredBuffer.fb.bindWrite(true).
        return PortalRenderInfo.renderAndDecideVisibility(portal, () -> {

            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                getCurrentProjectionMatrix(),
                true, false, false, true
            );
        });
    }

    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            return;
        }

        CHelper.checkGlError();

        // save the main framebuffer to deferredBuffer
        IPIrisHelper.newCopyDepthStencil(
            client.gameRenderer.mainRenderTarget(),
            deferredBuffer.fb
        );
        IPIrisHelper.copyColor(
            client.gameRenderer.mainRenderTarget(),
            deferredBuffer.fb
        );

        CHelper.checkGlError();

        renderPortals(passingModelView);

        // 26.2 (G8): no mainFrameBuffer.bindWrite(true).
        MyRenderHelper.drawScreenFrameBuffer(
            deferredBuffer.fb,
            false,
            false
        );
    }

    @Override
    public void onHandRenderingEnded() {

    }

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
