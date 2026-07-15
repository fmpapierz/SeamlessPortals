package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import qouteall.q_misc_util.Helper;

// S12-A (U10 A1 slice) port disposition: NEW (IP-add, current-mod-render §5; the mod's proven analog is the
// PortalContextSwitch `secondaryFbo` TextureTarget). Ports IP:render/SecondaryFrameBuffer.java VERBATIM,
// re-expressed onto the 26.2 RenderTarget/TextureTarget surface. Held/inert until S13; only the FBO-path
// renderers (RendererUsingFrameBuffer, renderMode=compatibility, A1) + the never-loaded Iris shells consume
// it. "It will always be the same size as the main frame buffer."
//
// 26.2 RE-EXPRESSIONS (each api-map-sanctioned; no IP LOGIC deviated):
//   * Minecraft.getMainRenderTarget()          -> gameRenderer.mainRenderTarget()      (render-core G12/C7)
//   * RenderTarget.viewWidth / viewHeight      -> RenderTarget.width / height          (render-core G12)
//   * new TextureTarget(w, h, useDepth, ON_OSX)-> new TextureTarget(label, w, h, useDepth, GpuFormat)
//                                                 (render-core C37/G13; Minecraft.ON_OSX GONE). Standard
//                                                 color = RGBA8_UNORM (G13); useDepth=true attaches a
//                                                 D32_FLOAT depth texture (RenderTarget.createBuffers:85-86).
//   * fb.checkStatus()                          -> DROPPED (render-core G12; the GpuDevice validates targets,
//                                                 no status API). The TextureTarget ctor calls resize() ->
//                                                 createBuffers() itself, so no explicit build step is needed.
//   * fb.resize(w, h, ON_OSX)                   -> fb.resize(w, h)   (RenderTarget.java:36; 3rd arg removed)
//
// STENCIL NOTE: IP's SecondaryFrameBuffer carried no stencil attachment of its own — stencil-capability was
// toggled per-target via the GONE IPPortingLibCompat/IEFrameBuffer hook (render-sub G8). On 26.2 stencil is
// absent from the RenderTarget/GPU abstraction (raw-GL only, current-mod-render row 12); RendererUsingFrameBuffer
// DISABLES stencil on this secondary FBO anyway, and the never-loaded Iris shells' stencil use is the
// documented S18/never-run iris gap. So the plain color+depth TextureTarget is the faithful 26.2 form here.
//
// SIGN NOTE (D4.4): this class allocates a target; it clears/compares no depth and writes no stencil, so R5
// reversed-Z does not touch it. Init/resize Helper.log calls fire ONLY on size change (never per-frame), so
// the render-thread-logging discipline (memory render-thread-logging-log4j-stall) is respected — VERBATIM IP.
public class SecondaryFrameBuffer {
    public TextureTarget fb;

    public void prepare() {
        RenderTarget mainFrameBuffer = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int width = mainFrameBuffer.width;
        int height = mainFrameBuffer.height;
        prepare(width, height);
    }

    public void prepare(int width, int height) {
        if (fb == null) {
            fb = new TextureTarget(
                "seamlessportals secondary",
                width, height,
                true,//has depth attachment
                GpuFormat.RGBA8_UNORM
            );
            Helper.log("Secondary Framebuffer init");
        }
        if (width != fb.width ||
            height != fb.height
        ) {
            fb.resize(width, height);
            Helper.log("Secondary Framebuffer resized");
        }
    }


}
