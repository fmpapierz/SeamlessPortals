package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.HashMap;

// S11-C port disposition: VERBATIM IP logic (IP:render/GuiPortalRendering.java) re-expressed onto the mod's
// proven 26.2 FBO/RenderTarget surface. Held/inert until S13 (ip_scc_closed filter). Renders a WorldRenderInfo
// into a caller-supplied RenderTarget (GUI/map/painting portal views), deferred to the game-render tail.
//
// The IP body drove the 1.21.3 immediate-mode RenderTarget bind/clear model, ALL of which is GONE on 26.2
// (render-core G12/G14, render-sub G8/C7/C8). Re-expressions (each api-map-sanctioned; no IP LOGIC deviated):
//   * Minecraft.getMainRenderTarget() -> minecraft.gameRenderer.mainRenderTarget() (G14/C7). 2 sites.
//   * RenderTarget.bindWrite(boolean) -> DROPPED (G8): 26.2 RenderTarget has no bind method / no FBO id. The
//     draw target is routed by the ip_setFrameBuffer swap of GameRenderer.mainRenderTarget, which
//     LevelRenderer re-reads every frame (render-core C5) — the mod's SecondaryFrameBuffer path already
//     crossed this bridge. So the swap IS the bind; the explicit bindWrite calls are superseded.
//   * RenderTarget.setClearColor + clear(boolean) -> a device-level clear of the target textures
//     (clearColorAndDepthTextures / clearColorTexture, CommandEncoder.java:211-228). Depth clear value is
//     0.0 = FAR under 26.2 reversed-Z (R5; was 1.0 in 1.21.3 normal-Z). Branch on RenderTarget.useDepth,
//     exactly mirroring IP's RenderTarget._clear (color always; depth iff useDepth).
//   * GlStateManager._colorMask(true,true,true,true) -> DROPPED: the 4-boolean overload is GONE on 26.2
//     (only a single-int WriteMask overload survives, GlStateManager.java:441), and it is unnecessary — the
//     device clear writes all four channels unconditionally, so IP's "make alpha writable before the
//     0-alpha clear" intent is satisfied inherently.
//   * RenderTarget.resize(w, h, boolean) -> resize(w, h) (the 3rd clearError boolean removed, RenderTarget.java:36).
//
// Documented forward-ref debt (NOT translation slips; already in the probe ledger):
//   - IPCGlobal.renderer (PortalRenderer: prepareRendering/invokeWorldRendering/finishRendering) resolves U10 / S12.

@Environment(EnvType.CLIENT)
public class GuiPortalRendering {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private static RenderTarget renderingFrameBuffer = null;

    @Nullable
    public static RenderTarget getRenderingFrameBuffer() {
        return renderingFrameBuffer;
    }

    public static boolean isRendering() {
        return getRenderingFrameBuffer() != null;
    }

    private static void renderWorldIntoFrameBuffer(
        WorldRenderInfo worldRenderInfo,
        RenderTarget framebuffer
    ) {
        RenderStates.basicProjectionMatrix = null;

        CHelper.checkGlError();

        ((IECamera) RenderStates.originalCamera).ip_resetState(
            worldRenderInfo.cameraPos, worldRenderInfo.world
        );

        Validate.isTrue(renderingFrameBuffer == null);
        renderingFrameBuffer = framebuffer;

        MyRenderHelper.restoreViewPort();

        RenderTarget mcFb = MyGameRenderer.client.gameRenderer.mainRenderTarget();

        Validate.isTrue(mcFb != framebuffer);

        ((IEMinecraftClient) MyGameRenderer.client).ip_setFrameBuffer(framebuffer);

        if (!worldRenderInfo.doRenderSky) {
            // pre-clear the framebuffer with 0 alpha, if it doesn't render the sky
            // 26.2 (G12/G39/C8): the immediate-mode setClearColor + clear(boolean) + bound-FBO glClear are
            // GONE; the equivalent is a device-level clear of the target's textures. Clears color to 0-alpha
            // (so the FBO composites transparently where nothing draws) and — mirroring IP's clear(true),
            // which cleared depth iff useDepth — clears depth to 0.0 = FAR under reversed-Z when the target
            // has a depth attachment.
            if (framebuffer.useDepth) {
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    framebuffer.getColorTexture(), new Vector4f(0, 0, 0, 0),
                    framebuffer.getDepthTexture(), 0.0
                );
            }
            else {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                    framebuffer.getColorTexture(), new Vector4f(0, 0, 0, 0)
                );
            }
        }

        // 26.2 (G8): no framebuffer.bindWrite — the ip_setFrameBuffer swap above routes rendering into this
        // target (LevelRenderer re-reads mainRenderTarget every frame, render-core C5).

        IPCGlobal.renderer.prepareRendering();

        IPCGlobal.renderer.invokeWorldRendering(worldRenderInfo);

        IPCGlobal.renderer.finishRendering();

        ((IEMinecraftClient) MyGameRenderer.client).ip_setFrameBuffer(mcFb);

        // 26.2 (G8): no mcFb.bindWrite — restoring the mainRenderTarget via ip_setFrameBuffer is sufficient.

        renderingFrameBuffer = null;

        MyRenderHelper.restoreViewPort();

        CHelper.checkGlError();

        RenderStates.basicProjectionMatrix = null;
    }

    private static final HashMap<RenderTarget, WorldRenderInfo> renderingTasks = new HashMap<>();

    public static void submitNextFrameRendering(
        WorldRenderInfo worldRenderInfo,
        RenderTarget renderTarget
    ) {
        if (!ClientWorldLoader.getIsInitialized()) {
            LOGGER.error("Trying to submit world rendering task before client world is initialized", new Throwable());
            return;
        }

        Validate.isTrue(!renderingTasks.containsKey(renderTarget));

        RenderTarget mcFB = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (renderTarget.width != mcFB.width || renderTarget.height != mcFB.height) {
            renderTarget.resize(mcFB.width, mcFB.height);
            LOGGER.info("Resized Framebuffer for GUI Portal Rendering");
        }

        renderingTasks.put(renderTarget, worldRenderInfo);
    }

    // Not API
    public static void _onGameRenderEnd() {
        renderingTasks.forEach((frameBuffer, worldRendering) -> {
            renderWorldIntoFrameBuffer(
                worldRendering, frameBuffer
            );
        });
        renderingTasks.clear();
    }

    // not API
    public static void _init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(renderingTasks::clear);
    }
}
