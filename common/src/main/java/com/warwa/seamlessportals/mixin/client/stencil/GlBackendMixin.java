package com.warwa.seamlessportals.mixin.client.stencil;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds stencil buffer support to the GLFW window.
 * Vanilla does NOT request stencil bits. We add GLFW_STENCIL_BITS = 8
 * so the default framebuffer has an 8-bit stencil buffer.
 *
 * This is REQUIRED for the stencil-based portal rendering approach
 * used by Immersive Portals.
 *
 * <p><b>26.3 PORT (migration/MC_26_3_VERSION_DELTA.md §2):</b> Minecraft 26.3 dropped GLFW for SDL3
 * ({@code org.lwjgl:lwjgl-glfw} is no longer a game library; {@code lwjgl-sdl:3.4.3} replaces it), so
 * both halves of the 26.2 hook are gone: {@code GlBackend.setWindowHints()} no longer exists and there
 * is no {@code GLFW.glfwWindowHint}. Vanilla now sets its context attributes at the top of
 * {@code GlBackend.createWindow(String,int,int,long)J} and creates the window in the same method.
 * Same intent, same position in the sequence as the 26.2 {@code setWindowHints} TAIL: request 8
 * stencil bits AFTER all of vanilla's attribute calls and BEFORE the window exists.
 *
 * <p>javap on the 26.3 merged jar ({@code com.mojang.renderpearl.backend.opengl.GlBackend}):
 * {@code createWindow} = five {@code invokestatic SDLVideo.SDL_GL_SetAttribute:(II)Z} (attrs
 * 17,18,20,19,22) followed by exactly ONE
 * {@code invokestatic SDLVideo.SDL_CreateWindow:(Ljava/lang/CharSequence;IIJ)J}, then {@code lreturn}
 * — hence {@code require = allow = 1} on that INVOKE. javap on {@code lwjgl-sdl-3.4.3.jar}:
 * {@code SDLVideo.SDL_GL_STENCIL_SIZE = 7} (the GLFW_STENCIL_BITS analogue).
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {

    @Inject(
        method = "createWindow",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J"
        ),
        require = 1,
        allow = 1
    )
    private void seamlessportals$addStencilBits(CallbackInfoReturnable<Long> cir) {
        // GLFW_STENCIL_BITS = 0x00021008 = 135176  (26.2)  ->  SDL_GL_STENCIL_SIZE = 7  (26.3)
        // Request 8 stencil bits for the window framebuffer
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_STENCIL_SIZE, 8);
        SeamlessPortalsConstants.LOGGER.info("[SEAMLESS STENCIL] Requested 8 stencil bits from SDL");
    }
}
