package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_INCR;
import static org.lwjgl.opengl.GL11.GL_KEEP;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glStencilFunc;
import static org.lwjgl.opengl.GL11.glStencilOp;

// S12-A Iris compile-SHELL — VERBATIM IP logic re-expressed onto the 26.2 surface. The renderMode=normal
// renderer WHEN Iris shaders are active: a multi-layer deferred-FBO stencil path. This class NEVER LOADS on
// 26.2 (Iris has no 26.2 build; IrisInterface.isIrisPresent()=false forever; PortalRenderer.switchToCorrectRenderer
// touches `instance` only inside the never-taken isIrisPresent() branch). It must only COMPILE. Held/inert
// until S13; A1 periphery (CUTOVER_SPEC §6.5).
//
// 26.2 RE-EXPRESSIONS (api-map-sanctioned) + the UNPORTABLE raw-FBO-id core:
//   * Minecraft.getMainRenderTarget()          -> client.gameRenderer.mainRenderTarget() (G14/C7).
//   * RenderTarget.viewWidth/viewHeight        -> width/height (G12).
//   * RenderTarget.bindWrite/unbindWrite       -> DROPPED (G8): no bind/FBO-id on 26.2.
//   * fb.checkStatus()                          -> DROPPED (G12): the GpuDevice validates.
//   * GlStateManager._clearColor/_clearDepth    -> device clear of the target textures (G7); depth 0.0 (R5 FAR).
//   * GlStateManager._colorMask(4-bool)         -> GL11.glColorMask (raw; the 4-bool overload is GONE).
//   * fb.blitToScreen(w,h)                       -> blitAndBlendToTexture (vanilla FBO->tex blit, RenderTarget.java:97).
//   * RenderSystem.getProjectionMatrix()        -> getCurrentProjectionMatrix() (G27/G19).
//   * MyRenderHelper.drawScreenFrameBuffer       -> the S12-A draw family.
//   * ** UNPORTABLE **: the deferred-FBO<->FBO raw-GL depth/stencil BLITS (GL30.glBindFramebuffer(fb.frameBufferId)
//     + glBlitFramebuffer) rely on RenderTarget.frameBufferId, which is GONE on 26.2 (no GL FBO id on the
//     GpuTexture/GpuTextureView abstraction, render-sub G8). There is no 26.2 core-profile analog for reading a
//     RenderTarget's FBO id, so those blit BLOCKS are commented out with this note — the exact IP mechanism for
//     portal-in-portal under Iris shaders. Never-run (Iris absent), so this is an inert S18-only gap, not a
//     behavior change. This mirrors IP's own discipline of commenting Iris internals with no vanilla analog
//     (cf. ShadowMapSwapper, the shadow-map cache block in ExperimentalIris.invokeWorldRendering).
//
// SIGN NOTE (D4.4 / R5): depth constants are the deferred-FBO CLEARS (1.0 -> 0.0, reversed-Z FAR); stencil ops
// are raw GL (direction-independent). Never runs — no live sign risk.
public class IrisPortalRenderer extends PortalRenderer {
    public static final IrisPortalRenderer instance = new IrisPortalRenderer();


    private SecondaryFrameBuffer[] deferredFbs = new SecondaryFrameBuffer[0];

    private boolean portalRenderingNeeded = false;
    private boolean nextFramePortalRenderingNeeded = false;

    IrisPortalRenderer() {
        IPGlobal.PRE_GAME_RENDER_EVENT.register(() -> {
            updateNeedsPortalRendering();
        });
    }

    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }

    @Override
    public void prepareRendering() {
        Validate.isTrue(!PortalRendering.isRendering());

        // As I tested, in Nvidia videocard, glCopyImageSubData can convert depth32 into depth24stencil8.
        // but in AMD videocard it cannot. AMD videocard only supports converting depth32 into depth32stencil8.
        IPCGlobal.useSeparatedStencilFormat = !IPMcHelper.isNvidiaVideocard();

        if (deferredFbs.length != PortalRendering.getMaxPortalLayer() + 1) {
            for (SecondaryFrameBuffer fb : deferredFbs) {
                fb.fb.destroyBuffers();
            }

            deferredFbs = new SecondaryFrameBuffer[PortalRendering.getMaxPortalLayer() + 1];
            for (int i = 0; i < deferredFbs.length; i++) {
                deferredFbs[i] = new SecondaryFrameBuffer();
            }
        }

        CHelper.checkGlError();

        for (SecondaryFrameBuffer deferredFb : deferredFbs) {
            deferredFb.prepare();
            IPPortingLibCompat.setIsStencilEnabled(deferredFb.fb, true);

            // 26.2 (G7/G8): IP bound the FBO then _clearColor(1,0,1,0)+_clearDepth(1)+_clearStencil(0)+glClear.
            // Device-clear color+depth (depth 0.0 = R5 reversed-Z FAR). The stencil clear has no bound-FBO
            // analog here (S18/never-run Iris gap).
            if (deferredFb.fb.getColorTextureView() != null) {
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    deferredFb.fb.getColorTexture(), new Vector4f(1, 0, 1, 0),
                    deferredFb.fb.getDepthTexture(), 0.0
                );
            }

            CHelper.checkGlError();
        }

        IPPortingLibCompat.setIsStencilEnabled(client.gameRenderer.mainRenderTarget(), false);

        // Iris now use vanilla framebuffer's depth
        // 26.2 (G8): no bindWrite(false).
    }

    private void updateNeedsPortalRendering() {
        portalRenderingNeeded = nextFramePortalRenderingNeeded;
        nextFramePortalRenderingNeeded = false;
    }

    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
        doMainRenderings(modelView);
    }

    private void doMainRenderings(Matrix4f modelView) {
        CHelper.checkGlError();

        RenderTarget mcFrameBuffer = client.gameRenderer.mainRenderTarget();
        int portalLayer = PortalRendering.getPortalLayer();

        if (portalRenderingNeeded) {
            CHelper.doCheckGlError();

            // ** UNPORTABLE (see class note) **: IP copied depth from the mc FB to the deferred FB via
            // GL30.glBindFramebuffer(fb.frameBufferId) + glBlitFramebuffer, then, on GL error, downgraded the
            // renderMode to compatibility. RenderTarget.frameBufferId is GONE on 26.2 (render-sub G8) with no
            // core-profile analog, so the blit + its error-driven downgrade are commented out. Never-run.
            /*
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mcFrameBuffer.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, deferredFbs[portalLayer].fb.frameBufferId);
            GL30.glBlitFramebuffer(
                0, 0, mcFrameBuffer.width, mcFrameBuffer.height,
                0, 0, mcFrameBuffer.width, mcFrameBuffer.height,
                GL_DEPTH_BUFFER_BIT, GL_NEAREST
            );
            int errorCode = GL11.glGetError();
            if (errorCode != GL_NO_ERROR) {
                IPGlobal.renderMode = IPGlobal.RenderMode.compatibility;
                CHelper.printChat("[Immersive Portals]" +
                    "Switched to compatibility portal rendering mode." +
                    " Portal-in-portal wont' be rendered");
            }
            */

            initStencilForLayer(portalLayer);

            // 26.2 (G8): no deferredFbs[portalLayer].fb.bindWrite(true).

            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_EQUAL, portalLayer, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

            // draw from mc fb into deferred fb within stencil
            MyRenderHelper.drawScreenFrameBuffer(mcFrameBuffer, false, true);

            glDisable(GL_STENCIL_TEST);

            // 26.2 (G8): no unbindWrite / mcFrameBuffer.bindWrite(false).
        }

        renderPortals(modelView);

        if (portalLayer == 0) {
            finish();
        }

        // 26.2 (G8): no mcFrameBuffer.bindWrite(true).
    }

    @Override
    public void onHandRenderingEnded() {

    }

    private void initStencilForLayer(int portalLayer) {
        if (portalLayer == 0) {
            // 26.2 (G8): no deferredFbs[0].fb.bindWrite(true). Raw-GL stencil clear on the current target.
            GL11.glClearStencil(0);
            GL11.glClear(GL_STENCIL_BUFFER_BIT);
        }
        else {
            CHelper.checkGlError();

            // ** UNPORTABLE (see class note) **: IP copied the outer layer's stencil into this layer's FBO via
            // frameBufferId + glBlitFramebuffer(GL_STENCIL_BUFFER_BIT). No 26.2 FBO-id analog. Commented; never-run.
            /*
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, deferredFbs[portalLayer - 1].fb.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, deferredFbs[portalLayer].fb.frameBufferId);
            GL30.glBlitFramebuffer(
                0, 0, deferredFbs[0].fb.width, deferredFbs[0].fb.height,
                0, 0, deferredFbs[0].fb.width, deferredFbs[0].fb.height,
                GL_STENCIL_BUFFER_BIT, GL_NEAREST
            );
            */

            CHelper.checkGlError();
        }
    }

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {

    }


    @Override
    public void finishRendering() {

    }

    private void finish() {
        // 26.2: RenderSystem.colorMask(4-bool) GONE -> raw GL11.glColorMask.
        GL11.glColorMask(true, true, true, true);

        if (RenderStates.getRenderedPortalNum() == 0) {
            return;
        }

        if (!portalRenderingNeeded) {
            return;
        }

        RenderTarget mainFrameBuffer = client.gameRenderer.mainRenderTarget();
        // 26.2 (G8): no mainFrameBuffer.bindWrite(true).

        // 26.2: fb.blitToScreen(w,h) GONE -> blitAndBlendToTexture onto the main target's views.
        if (deferredFbs[0].fb.getColorTextureView() != null
            && mainFrameBuffer.getColorTextureView() != null
            && mainFrameBuffer.getDepthTextureView() != null) {
            deferredFbs[0].fb.blitAndBlendToTexture(
                mainFrameBuffer.getColorTextureView(), mainFrameBuffer.getDepthTextureView()
            );
        }

        CHelper.checkGlError();
    }

    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        nextFramePortalRenderingNeeded = true;

        if (!portalRenderingNeeded) {
            return;
        }

        //write to deferred buffer
        if (!tryRenderViewAreaInDeferredBufferAndIncreaseStencil(portal, modelView)) {
            return;
        }

        PortalRendering.pushPortalLayer(portal);

        // this is important
        // 26.2 (G8): no client.getMainRenderTarget().bindWrite(true).

        renderPortalContent(portal);

        int innerLayer = PortalRendering.getPortalLayer();

        PortalRendering.popPortalLayer();

        int outerLayer = PortalRendering.getPortalLayer();

        if (innerLayer > PortalRendering.getMaxPortalLayer()) {
            return;
        }

        // 26.2 (G8): no deferredFbs[outerLayer].fb.bindWrite(true).

        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, innerLayer, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        MyRenderHelper.drawScreenFrameBuffer(
            deferredFbs[innerLayer].fb,
            true,
            false
        );

        glDisable(GL_STENCIL_TEST);

        // 26.2 (G8): no deferredFbs[outerLayer].fb.unbindWrite().
    }

    private boolean tryRenderViewAreaInDeferredBufferAndIncreaseStencil(
        Portal portal, Matrix4f modelView
    ) {

        int portalLayer = PortalRendering.getPortalLayer();

        initStencilForLayer(portalLayer);

        // 26.2 (G8): no deferredFbs[portalLayer].fb.bindWrite(true).

        GL11.glEnable(GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, portalLayer, 0xFF);
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);

        GlStateManager._enableDepthTest();

        boolean result = PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                getCurrentProjectionMatrix(),
                true, true, true, true
            );
        });

        GL11.glDisable(GL_STENCIL_TEST);

        return result;
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

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
